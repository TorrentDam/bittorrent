package com.github.lavrov.bittorrent.protocol

import cats.data._
import cats.MonadError
import cats.instances.all._
import cats.syntax.all._
import cats.mtl._
import cats.mtl.instances.all._
import com.github.lavrov.bittorrent.protocol.message.Message
import scodec.bits.{BitVector, ByteVector}

import scala.collection.immutable.ListSet
import scala.concurrent.duration._

class Protocol(
    keepAliveInterval: FiniteDuration,
) {
  import Protocol._

  val Env: ApplicativeAsk[Thunk, Long] = implicitly
  val S: MonadState[Thunk, ProtocolState] = implicitly
  val F: MonadError[Thunk, Throwable] = implicitly
  val T: FunctorTell[Thunk, Chain[Eff]] = implicitly

  def handleMessage(msg: Message): Thunk[Unit] = {
    for {
      time <- Env.ask
      _ <- msg match {
        case Message.KeepAlive => F.unit
        case Message.Choke =>
          S.modify(_.copy(peerChoking = true))
        case Message.Unchoke =>
          for {
            _ <- S.modify(_.copy(peerChoking = false))
            _ <- requestPiece
          } yield ()
        case Message.Interested =>
          S.modify(_.copy(peerInterested = true))
        case Message.NotInterested =>
          S.modify(_.copy(peerInterested = false))
        case piece: Message.Piece => receivePiece(piece)
        case Message.Bitfield(bytes) =>
          S.modify(_.copy(bitfield = bytes.bits.some))
      }
      _ <- S.modify(_.copy(lastMessageAt = time))
    } yield ()
  }

  def requestPiece: Thunk[Unit] = {
    for {
      state <- S.get
      _ <-
        if (state.peerChoking)
          F.unit
        else
          state.queue.headOption match {
            case Some(request) =>
              for {
                _ <- S.set(
                  state.copy(
                    queue = state.queue.tail,
                    pending = state.pending + request
                  )
                )
                _ <- T.tell(Chain one Eff.Send(request))
              }
              yield ()
            case None => F.unit
          }

    } yield ()
  }

  def submitDownload(index: Long, begin: Long, length: Long): Thunk[Unit] = {
    for {
      state <- S.get
      request = Message.Request(index, begin, length)
      _ <- S.set(state.copy(queue = state.queue + request))
      _ <- requestPiece
    } yield ()
  }

  def sendKeepAlive: Thunk[Unit] = {
    for {
      state <- S.get
      time <- Env.ask
      durationSinceLastMessage = (time - state.lastMessageAt).millis
      _ <- if (durationSinceLastMessage > keepAliveInterval)
        T.tell(Chain one Eff.Send(Message.KeepAlive))
      else
        F.unit
      _ <- T.tell(Chain one Eff.Schedule(keepAliveInterval, sendKeepAlive))
    } yield ()
  }

  def receivePiece(piece: Message.Piece): Thunk[Unit] = {
    for {
      state <- S.get
      request = Message.Request(piece.index, piece.begin, piece.bytes.length)
      inPending = state.pending.contains(request)
      _ <- if (inPending)
        for {
          _ <- T.tell(Chain one Eff.ReturnPiece(piece.index, piece.begin, piece.bytes))
          _ <- S.set(
            state.copy(
              pending = state.pending.filterNot(_ == request)
            )
          )
          _ <- requestPiece
        }
        yield ()
      else
        F.raiseError(new Exception("Unexpected piece"))

    } yield ()
  }
}

object Protocol {
  type Thunk[A] = RWST[Either[Throwable, ?], Long, Chain[Eff], ProtocolState, A]

  case class ProtocolState(
    lastMessageAt: Long = 0,
    choking: Boolean = true,
    interested: Boolean = false,
    peerChoking: Boolean = true,
    peerInterested: Boolean = false,
    bitfield: Option[BitVector] = None,
    queue: ListSet[Message.Request] = ListSet.empty,
    pending: ListSet[Message.Request] = ListSet.empty,
  )
  sealed trait Eff
  object Eff {
    final case class Send(message: Message) extends Eff
    final case class Schedule(in: FiniteDuration, thunk: Thunk[Unit]) extends Eff
    final case class ReturnPiece(index: Long, begin: Long, bytes: ByteVector) extends Eff
  }

  val materialized = new Protocol(10.seconds)

  def run(thunk: Thunk[Unit], time: Long, state: ProtocolState) = thunk.run(time, state)
}
