package com.github.lavrov.bittorrent.protocol
import java.util.concurrent.TimeUnit

import cats.{Monad, Traverse}
import cats.syntax.functor._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.instances.list._
import cats.syntax.option._
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
  case class State(
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
    final case class Schedule(in: FiniteDuration, cmd: Command) extends Eff
    final case class Many(eff: List[Eff]) extends Eff
    case object No extends Eff
  }

  sealed trait Command
  object Command {
    case object KeepAlive extends Command
    final case class Download(index: Long, length: Long) extends Command
  }

}

class PeerCommunication[F[_]: Timer: Concurrent: Sync: Monad] {
  import PeerCommunication._

  case class Handle(send: Command => F[Unit], fiber: Fiber[F, Unit])

  def run(selfId: PeerId, infoHash: InfoHash, connection: Connection[F]): F[Handle] = {
    for {
      handshake <- connection.handshake(selfId, infoHash)
      _ = println(s"Successful handshake $handshake")
      queue <- InspectableQueue.unbounded[F, Command]
      _ <- queue.enqueue1(Command.KeepAlive)
      initialState = State(0)
      inlet =
        Stream.eval(connection.receive).map(Right(_)).repeat merge
        queue.dequeue.map(Left(_))
      process =
        inlet.evalScan(initialState){ (state, input) =>
          for {
            time <- Timer[F].clock.realTime(TimeUnit.MILLISECONDS)
            state <- eventLoop(time, state, input) match {
              case (s, eff) => runEff(eff, queue, connection).map(_ => s)
            }
          }
          yield state
        }
      fiber <- Concurrent[F].start(process.compile.drain)
    }
    yield Handle(queue.enqueue1, fiber)
  }


  def eventLoop(time: Long, state: State, in: Either[Command, Message]): (State, Eff) = in.fold(
    {
      case Command.KeepAlive =>
        val sendKeepAlive = (time - state.lastMessageAt).millis > 60.seconds
        val effects = Eff.Many(
          Eff.Schedule(60.seconds, Command.KeepAlive) ::
          (if (sendKeepAlive) List(Eff.Send(Message.KeepAlive)) else Nil)
        )
        (state, effects)
      case Command.Download(index, length) =>
        val chunkSize = 16 * 1024
        val fullChunks = length / chunkSize
        val reminder = length % chunkSize
        val s = state.copy(
          queue = (0L until fullChunks).map(i => Request(index, i * chunkSize, chunkSize)).to[ListSet] ++
            (if (reminder > 0) Request(index, fullChunks * chunkSize, reminder).some else none).to[ListSet]
        )
        val e = if (state.interested) Eff.No else Eff.Send(Message.Interested)
        (s, e)
    },
    {
      case Choke => (state.copy(peerChoking = true), Eff.No)
      case Unchoke =>
        val s = state.copy(peerChoking = false)
        val e = state.queue.headOption.fold[Eff](Eff.No)(Eff.Send)
        (s, e)
      case Interested => (state.copy(peerInterested = true), Eff.No)
      case NotInterested => (state.copy(peerInterested = false), Eff.No)
      case Bitfield(bytes) => (state.copy(bitfield = bytes.bits.some), Eff.No)
      case Piece(index, begin, bytes) =>
        val request = Request(index, begin, bytes.length)
        if (state.queue contains request) {
          val s =
            state.copy(
              queue = state.queue - request,
              pieces = state.pieces.updated(request, bytes)
            )
          val e = s.queue.headOption.fold[Eff](Eff.No)(Eff.Send)
          (s, e)
        }
        else
          (state, Eff.No)
      case _ => (state.copy(lastMessageAt = time), Eff.No)
    }
  )

  def runEff(eff: Eff, commandQueue: Queue[F, Command], connection: Connection[F]): F[Unit] = eff match {
    case Eff.No => Monad[F].unit
    case Eff.Many(effects) => Traverse[List].sequence(effects.map(runEff(_, commandQueue, connection))) *> Monad[F].unit
    case Eff.Send(message) => connection.send(message)
    case Eff.Schedule(in, cmd) => (Timer[F].sleep(in) *> commandQueue.enqueue1(cmd)).start.void
  }
}
