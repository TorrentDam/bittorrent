package com.github.lavrov.bittorrent.dht

import cats.{Monad, MonadError}
import cats.syntax.all._
import cats.mtl.MonadState
import cats.mtl.implicits._
import com.github.lavrov.bittorrent.dht.message.Message
import fs2.{Pipe, Pull, Stream}
import java.net.InetSocketAddress
import com.github.lavrov.bittorrent.dht.message.Query
import com.github.lavrov.bittorrent.dht.message.Response
import com.github.lavrov.bittorrent.InfoHash
import com.github.lavrov.bittorrent.PeerInfo
import monocle.Lens
import monocle.macros.GenLens
import cats.effect.IO
import com.github.lavrov.bittorrent.TorrentMetadata.Info
import scodec.bits.ByteVector
import com.github.lavrov.bencode.Bencode
import cats.effect.Concurrent
import cats.effect.concurrent.Ref
import fs2.concurrent.Queue
import cats.effect.concurrent.Deferred
import cats.effect.Sync
import cats.effect.Timer

import scala.concurrent.duration._

class DhtBehaviour[F[_]](
    selfId: NodeId,
    sendOut: (InetSocketAddress, Message) => F[Unit]
)(
    implicit F: Monad[F],
    S: MonadState[F, DhtBehaviour.State]
) {
  import DhtBehaviour._

  def getTable: F[List[NodeInfo]] = S.get.map(_.table)

  def pipe: Pipe[F, (InetSocketAddress, Message), Unit] = { input =>
    input.evalMap { case (address, message) => onMessage(address, message) }
  }

  private def onMessage(address: InetSocketAddress, message: Message): F[Unit] = {
    message match {
      case Message.QueryMessage(tid, Query.Ping(nodeId)) =>
        sendOut(address, Message.ResponseMessage(tid, Response.Ping(selfId)))
      case Message.QueryMessage(tid, Query.GetPeers(nodeId, infoHash)) =>
        MonadState[F, State]
          .inspect(State.table.get)
          .flatMap { nodes =>
            val response = Response.Nodes(selfId, nodes)
            sendOut(address, Message.ResponseMessage(tid, response))
          }
      case Message.QueryMessage(tid, Query.FindNode(nodeId, target)) =>
        MonadState[F, State]
          .inspect(State.table.get)
          .flatMap { nodes =>
            val response = Response.Nodes(selfId, nodes)
            sendOut(address, Message.ResponseMessage(tid, response))
          }
      case Message.ResponseMessage(_, Response.Ping(nodeId)) =>
          S.modify(State.table.modify(l => NodeInfo(nodeId, address) :: l))
      case _ =>
        F.unit
    }
  }
}

object DhtBehaviour {
  import com.olegpy.meow.effects.RefEffects

  case class State(
      table: List[NodeInfo] = List.empty
  )
  object State {
    val table: Lens[State, List[NodeInfo]] = GenLens[State](_.table)
  }

  def make[F[_]: Sync](
    selfId: NodeId,
    sendOut: (InetSocketAddress, Message) => F[Unit]
  ): F[DhtBehaviour[F]] = for {
    ref <- Ref.of(State())
  }
  yield ref.runState { implicit S =>
    new DhtBehaviour(selfId, sendOut)
  }
}

trait RequestResponse[F[_]] {
  def ping(address: InetSocketAddress): F[Response.Ping]
  def getPeers(
      address: InetSocketAddress,
      infoHash: InfoHash
  ): F[Either[Response.Nodes, Response.Peers]]
  def pipe: Pipe[F, (InetSocketAddress, Message), Unit]
}

object RequestResponse {

  def make[F[_]](
      selfId: NodeId,
      generateTransactionId: F[ByteVector],
      sendOut: (InetSocketAddress, Message) => F[Unit]
  )(
    implicit F: Concurrent[F], timer: Timer[F]
  ): F[RequestResponse[F]] = {

    import com.olegpy.meow.effects.RefEffects

    type Callback = Either[Throwable, Response] => F[Unit]

    sealed trait In
    object In {
      case class Msg(message: Message) extends In
      case class Timeout(transactionId: ByteVector) extends In
    }

    type State = Map[ByteVector, Callback]

    for {
      ref <- Ref.of(Map.empty: State)
      timeoutQueue <- Queue.unbounded[F, In]
      val behaviour = ref.runState { implicit S =>
        new Behaviour[F, Callback](
          generateTransactionId,
          sendOut,
          (duration, transactionId) =>
            F.start(
              timer.sleep(duration) >> timeoutQueue.enqueue1(In.Timeout(transactionId))
            ).void,
          (callback, result) => callback(result)
        )
      }
    } yield {
      def sendQuery(address: InetSocketAddress, query: Query): F[Response] =
        Deferred[F, Either[Throwable, Response]].flatMap { d =>
          behaviour.sendQuery(address, query, d.complete) >>
            d.get >>= Concurrent[F].fromEither
        }
      new RequestResponse[F] {

        def ping(address: InetSocketAddress): F[Response.Ping] =
          sendQuery(address, Query.Ping(selfId)).flatMap {
            case ping: Response.Ping => ping.pure
            case _ => Concurrent[F].raiseError(InvalidResponse())
          }

        def getPeers(
            address: InetSocketAddress,
            infoHash: InfoHash
        ): F[Either[Response.Nodes, Response.Peers]] =
          sendQuery(address, Query.GetPeers(selfId, infoHash)).flatMap {
            case nodes: Response.Nodes => nodes.asLeft.pure
            case peers: Response.Peers => peers.asRight.pure
            case _ => Concurrent[F].raiseError(InvalidResponse())
          }

        def pipe = { input =>
          (input.map(_._2).map(In.Msg) merge timeoutQueue.dequeue).evalMap { in =>
            in match {
              case In.Msg(message) => behaviour.receive(message)
              case In.Timeout(transactionId) => behaviour.timeout(transactionId)
            } 
          }
        }
      }
    }
  }

  class Behaviour[F[_], Callback](
      generateTransactionId: F[ByteVector],
      sendOut: (InetSocketAddress, Message) => F[Unit],
      scheduleTimeout: (FiniteDuration, ByteVector) => F[Unit],
      runCallback: (Callback, Either[Throwable, Response]) => F[Unit]
  )(
      implicit
      F: Monad[F],
      S: MonadState[F, Map[ByteVector, Callback]]
  ) {

    def sendQuery(address: InetSocketAddress, query: Query, callback: Callback): F[Unit] = {
      generateTransactionId.flatMap { transactionId =>
        val message = Message.QueryMessage(transactionId, query)
        val send = sendOut(address, message)
        val addCallback = S.modify(s => s.updated(transactionId, callback))
        send >> addCallback >> scheduleTimeout(10.seconds, transactionId)
      }
    }

    def receive(message: Message): F[Unit] = message match {
      case Message.ResponseMessage(transactionId, response) =>
        continue(transactionId, response.asRight)
      case Message.ErrorMessage(transactionId, details) =>
        continue(transactionId, ErrorResponse(details).asLeft)
      case _ =>
        F.unit
    }

    def timeout(transactionId: ByteVector): F[Unit] = {
      continue(transactionId, Timeout().asLeft)
    }

    private def continue(
        transactionId: ByteVector,
        result: Either[Throwable, Response]
    ): F[Unit] = {
      S.inspect(_.get(transactionId)).flatMap {
        case Some(callback) =>
          val removeCallback = S.modify(_ - transactionId)
          removeCallback >> runCallback(callback, result)
        case None => F.unit
      }
    }
  }

  case class ErrorResponse(details: Bencode) extends Throwable
  case class InvalidResponse() extends Throwable
  case class Timeout() extends Throwable
}
