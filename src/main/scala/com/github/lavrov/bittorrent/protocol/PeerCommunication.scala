package com.github.lavrov.bittorrent.protocol
import java.util.concurrent.TimeUnit

import cats.{Monad, Traverse}
import cats.syntax.functor._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.instances.list._
import cats.effect.{Concurrent, Timer}
import com.github.lavrov.bittorrent.protocol.message.Message
import com.github.lavrov.bittorrent.{InfoHash, PeerId}
import fs2.Stream
import fs2.concurrent.{InspectableQueue, Queue}

import scala.concurrent.duration._

object PeerCommunication {
  case class State(
      lastMessageAt: Long
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
  }
}

class PeerCommunication[F[_]: Timer: Concurrent: Monad] {
  import PeerCommunication._

  def run(selfId: PeerId, infoHash: InfoHash, connection: Connection[F]): Stream[F, State] = {
    for {
      handshake <- Stream.eval(connection.handshake(selfId, infoHash))
      queue <- Stream.eval(InspectableQueue.unbounded[F, Command])
      initialState = State(0)
      state <- (
        Stream.eval(connection.receive).map(Right(_)).repeat merge
        queue.dequeue.map(Left(_))
      ).evalScan(initialState)((state, input) =>
        for {
          time <- Timer[F].clock.realTime(TimeUnit.MILLISECONDS)
          state <- eventLoop(time, state, input) match {
            case (s, eff) => runEff(eff, queue, connection).map(_ => s)
          }
        }
        yield state
      )
    }
    yield state
  }


  def eventLoop(time: Long, state: State, in: Either[Command, Message]): (State, Eff) = in.fold(
    {
      case Command.KeepAlive =>
        val sendKeepAlive = (time - state.lastMessageAt).millis > 5.seconds
        val effects = Eff.Many(
          Eff.Schedule(5.seconds, Command.KeepAlive) ::
          (if (sendKeepAlive) List(Eff.Send(Message.KeepAlive)) else Nil)
        )
        (state, effects)
    },
    {
      _ => (state.copy(lastMessageAt = time), Eff.No)
    }
  )

  def runEff(eff: Eff, commandQueue: Queue[F, Command], connection: Connection[F]): F[Unit] = eff match {
    case Eff.No => Monad[F].unit
    case Eff.Many(effects) => Traverse[List].sequence(effects.map(runEff(_, commandQueue, connection))) *> Monad[F].unit
    case Eff.Send(message) => connection.send(message)
    case Eff.Schedule(in, cmd) => Timer[F].sleep(in) *> commandQueue.enqueue1(cmd)
  }
}
