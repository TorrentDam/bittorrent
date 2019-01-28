package com.github.lavrov.bittorrent.protocol

import java.util.concurrent.TimeUnit

import cats._
import cats.data._
import cats.syntax.all._
import cats.effect.{Concurrent, Fiber, Sync, Timer}
import cats.instances.all._
import cats.mtl._
import cats.mtl.instances.all._
import cats.effect.syntax.concurrent._
import com.github.lavrov.bittorrent.protocol.PeerCommunication.Event
import com.github.lavrov.bittorrent.protocol.Protocol.ProtocolState
import com.github.lavrov.bittorrent.protocol.message.Message
import com.github.lavrov.bittorrent.{InfoHash, PeerId}
import fs2.Stream
import fs2.concurrent.{InspectableQueue, Queue}
import scodec.bits.ByteVector

import scala.concurrent.duration._

class PeerCommunication[F[_]: Timer: Concurrent: Sync: Monad] {

  type Thunk[A] = RWST[Either[Throwable, ?], Long, Chain[Eff], ProtocolState, A]

  sealed trait Eff
  object Eff {
    final case class Send(message: Message) extends Eff
    final case class Schedule(in: FiniteDuration, thunk: Thunk[Unit]) extends Eff
    final case class ReturnPiece(index: Long, begin: Long, bytes: ByteVector) extends Eff
  }

  val effects: Effects[Thunk, Eff] = new Effects[Thunk, Eff] {
    def Send(message: Message) = Eff.Send(message)
    def Schedule(in: FiniteDuration, thunk: Thunk[Unit]) = Eff.Schedule(in, thunk)
    def ReturnPiece(index: Long, begin: Long, bytes: ByteVector) = Eff.ReturnPiece(index, begin, bytes)
  }

  val protocol = new Protocol[Thunk, Eff](1.minute, effects)

  case class Handle(algebra: CommunicationAlg[F], events: Stream[F, Event], fiber: Fiber[F, Unit])

  def run(selfId: PeerId, infoHash: InfoHash, connection: Connection[F]): F[Handle] = {
    for {
      handshake <- connection.handshake(selfId, infoHash)
      _ = println(s"Successful handshake $handshake")
      thunkQueue <- InspectableQueue.unbounded[F, Thunk[Unit]]
      eventQueue <- InspectableQueue.unbounded[F, Event]
      incomingMessages = Stream.eval(connection.receive).repeat
      inlet = thunkQueue.dequeue.map(Left(_)) merge incomingMessages.map(Right(_))
      initialState = ProtocolState()
      process =
        inlet.evalScan(initialState){ (state, input) =>
          val thunk = input match {
            case Right(msg) => protocol.handleMessage(msg)
            case Left(v) => v
          }
          for {
            time <- Timer[F].clock.realTime(TimeUnit.MILLISECONDS)
            state <- thunk.run(time, state) match {
              case Right((effs, newState, _)) =>
                runEffs(effs, thunkQueue, eventQueue, connection) *> newState.pure[F]
              case Left(e) =>
                Sync[F].raiseError(e)
            }
          }
          yield state.copy(lastMessageAt = time)
        }
      fiber <- Concurrent[F].start(process.compile.drain)
      alg = new CommunicationAlg[F] {
        def download(index: Long, begin: Long, length: Long): F[Unit] = {
          thunkQueue.enqueue1(protocol.submitDownload(index, begin, length))
        }
      }
    }
    yield Handle(alg, eventQueue.dequeue, fiber)
  }

  def runEffs(effs: Chain[Eff], thunkQueue: Queue[F, Thunk[Unit]], eventQueue: Queue[F, Event], connection: Connection[F]): F[Unit] = {
    effs.uncons match {
      case None => Monad[F].unit
      case Some((eff, tail)) =>
        val result = eff match {
          case Eff.Send(message) => connection.send(message)
          case Eff.Schedule(in, thunk) => (Timer[F].sleep(in) *> thunkQueue.enqueue1(thunk)).start.void
          case Eff.ReturnPiece(index, begin, bytes) => eventQueue.enqueue1(Event.Downloaded(index, begin, bytes))
        }
        result *> runEffs(tail, thunkQueue, eventQueue, connection)
    }
  }
}

object PeerCommunication {
  sealed trait Event
  object Event {
    final case class Downloaded(index: Long, begin: Long, bytes: ByteVector) extends Event
  }
}

trait CommunicationAlg[F[_]] {
  def download(index: Long, begin: Long, length: Long): F[Unit]
}
