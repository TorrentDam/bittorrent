package com.github.lavrov.bittorrent.protocol

import cats.data._
import cats.MonadError
import cats.syntax.all._
import cats.mtl._
import com.github.lavrov.bittorrent.protocol.Protocol.ProtocolState
import com.github.lavrov.bittorrent.protocol.message.Message
import scodec.bits.{BitVector, ByteVector}

import scala.collection.immutable.ListSet
import scala.concurrent.duration._

class Protocol[F[_], E](
    keepAliveInterval: FiniteDuration,
    effects: Effects[F, E]
)(implicit
  Env: ApplicativeAsk[F, Long],
  S: MonadState[F, ProtocolState],
  F: MonadError[F, Throwable],
  T: FunctorTell[F, Chain[E]]
) {
  def handleMessage(msg: Message): F[Unit] = {
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

  def requestPiece: F[Unit] = {
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
                _ <- T.tell(Chain one effects.Send(request))
              }
              yield ()
            case None => F.unit
          }

    } yield ()
  }

  def submitDownload(index: Long, begin: Long, length: Long): F[Unit] = {
    for {
      state <- S.get
      request = Message.Request(index, begin, length)
      _ <- S.set(state.copy(queue = state.queue + request))
      _ <- requestPiece
    } yield ()
  }

  def sendKeepAlive: F[Unit] = {
    for {
      state <- S.get
      time <- Env.ask
      durationSinceLastMessage = (time - state.lastMessageAt).millis
      _ <- if (durationSinceLastMessage > keepAliveInterval)
        T.tell(Chain one effects.Send(Message.KeepAlive))
      else
        F.unit
      _ <- T.tell(Chain one effects.Schedule(keepAliveInterval, sendKeepAlive))
    } yield ()
  }

  def receivePiece(piece: Message.Piece): F[Unit] = {
    for {
      state <- S.get
      request = Message.Request(piece.index, piece.begin, piece.bytes.length)
      inPending = state.pending.contains(request)
      _ <- if (inPending)
        for {
          _ <- T.tell(Chain one effects.ReturnPiece(piece.index, piece.begin, piece.bytes))
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

trait Effects[F[_], E] {
  def Send(message: Message): E
  def Schedule(in: FiniteDuration, thunk: F[Unit]): E
  def ReturnPiece(index: Long, begin: Long, bytes: ByteVector): E
}

object Protocol {

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

}
