package com.github.lavrov.bittorrent.protocol
import java.util.concurrent.TimeUnit

import cats._
import cats.data._
import cats.syntax.all._
import cats.effect.{Concurrent, Fiber, Sync, Timer}
import cats.effect.syntax.concurrent._
import com.github.lavrov.bittorrent.protocol.message.Message
import com.github.lavrov.bittorrent.protocol.message.Message._
import com.github.lavrov.bittorrent.{InfoHash, PeerId}
import fs2.Stream
import fs2.concurrent.{InspectableQueue, Queue}
import scodec.bits.{BitVector, ByteVector}

import scala.collection.immutable.ListSet
import scala.concurrent.duration._

object PeerCommunication {

  type Thunk = RWS[Long, Chain[Eff], ComState, Unit]

  case class ComState(
      lastMessageAt: Long = 0,
      choking: Boolean = true,
      interested: Boolean = false,
      peerChoking: Boolean = true,
      peerInterested: Boolean = false,
      bitfield: Option[BitVector] = None,
      queue: ListSet[Request] = ListSet.empty,
      pieces: Map[Request, ByteVector] = Map.empty
  )
  sealed trait Eff
  object Eff {
    final case class Send(message: Message) extends Eff
    final case class Schedule(in: FiniteDuration, processor: Thunk) extends Eff
  }

}

class PeerCommunication[F[_]: Timer: Concurrent: Sync: Monad] {
  import PeerCommunication._

  case class Handle(send: Thunk => F[Unit], fiber: Fiber[F, Unit])

  def run(selfId: PeerId, infoHash: InfoHash, connection: Connection[F]): F[Handle] = {
    for {
      handshake <- connection.handshake(selfId, infoHash)
      _ = println(s"Successful handshake $handshake")
      thunkQueue <- InspectableQueue.unbounded[F, Thunk]
      initialState = ComState(0)
      inlet = Stream.eval(connection.receive).repeat
      process =
        inlet.evalScan(initialState){ (state, input) =>
          for {
            time <- Timer[F].clock.realTime(TimeUnit.MILLISECONDS)
            (effs, s, _) = standard(input).run(time, state).value
            _ <- runEffs(effs, thunkQueue, connection).map(_ => s)
          }
          yield state
        }
      fiber <- Concurrent[F].start(process.compile.drain)
    }
    yield Handle(thunkQueue.enqueue1, fiber)
  }

  def peerState(in: Message): RWS[Long, Chain[Eff], ComState, Unit] = in match {
    case Choke =>
      RWS.modify(_.copy(peerChoking = true))
    case Unchoke =>
      RWS.modify(_.copy(peerChoking = false))
    case Interested =>
      RWS.modify(_.copy(peerInterested = true))
    case NotInterested =>
      RWS.modify(_.copy(peerInterested = false))
    case _ =>
      RWS.modify(identity)
  }

  def bitfieldUpdate(in: Message): RWS[Long, Chain[Eff], ComState, Unit] = in match {
    case Bitfield(bytes) =>
      RWS.modify(_.copy(bitfield = bytes.bits.some))
    case _ =>
      RWS.modify(identity)
  }

  val keepAlive: Thunk =
    for {
      time <- RWS.ask[Long, Chain[Eff], ComState]
      state <- RWS.get[Long, Chain[Eff], ComState]
      _ <-
        if (state.lastMessageAt + 10000 < time)
          RWS.tell[Long, Chain[Eff], ComState](Chain one Eff.Send(KeepAlive))
        else
          RWS.tell[Long, Chain[Eff], ComState](Chain one Eff.Schedule(10000.millis, keepAlive))
    }
    yield ()

  def lastMessageTime(in: Message): RWS[Long, Chain[Eff], ComState, Unit] = {
    for {
      time <- RWS.ask[Long, Chain[Eff], ComState]
      _ <- RWS.modify[Long, Chain[Eff], ComState](_.copy(lastMessageAt = time))
    }
    yield ()
  }

  def standard(in: Message) = peerState(in) *> bitfieldUpdate(in) *> lastMessageTime(in)

  def runEffs(effs: Chain[Eff], thunkQueue: Queue[F, Thunk], connection: Connection[F]): F[Unit] = effs.uncons match {
    case None => Monad[F].unit
    case Some((eff, tail)) =>
      val result = eff match {
        case Eff.Send(message) => connection.send(message)
        case Eff.Schedule(in, processor) => (Timer[F].sleep(in) *> thunkQueue.enqueue1(processor)).start.void
      }
      result *> runEffs(tail, thunkQueue, connection)
  }
}
