package com.github.lavrov.bittorrent.wire

import cats.effect.Concurrent
import cats.syntax.all._
import com.github.lavrov.bittorrent.MetaInfo
import com.github.lavrov.bittorrent.protocol.extensions.metadata.Message
import com.github.lavrov.bittorrent.protocol.extensions.{ExtensionHandshake, Extensions}
import com.github.lavrov.bittorrent.protocol.message.Message.Extended
import fs2.Stream
import logstage.LogIO
import scodec.bits.ByteVector

trait ExtendedMessageSocket[F[_]] {
  def send(message: Extended): F[Unit]
  def receive: F[Extended]
}

object ExtensionHandshaker {
  def apply[F[_]](
    socket: ExtendedMessageSocket[F]
  )(implicit F: Concurrent[F]): F[ExtensionHandshake] = {
    for {
      _ <- socket.send(
        Extended(
          Extensions.MessageId.Handshake,
          ExtensionHandshake.encode(Extensions.handshakePayload)
        )
      )
      h <- socket.receive.flatMap {
        case Extended(Extensions.MessageId.Handshake, payload) =>
          F.fromEither(ExtensionHandshake.decode(payload))
        case _ => F.raiseError[ExtensionHandshake](HandshakeError())
      }
    } yield h
  }

  case class HandshakeError() extends Exception
}

object UtMetadata {
  def download[F[_]](messageId: Long, size: Long, socket: ExtendedMessageSocket[F])(
    implicit F: Concurrent[F],
    logger: LogIO[F]
  ): F[ByteVector] = {
    def receive: F[Message] =
      socket.receive.flatMap {
        case Extended(Extensions.MessageId.Metadata, messageBytes) =>
          F.fromEither(Message.decode(messageBytes)).flatTap { message =>
            logger.debug(s"Received $message")
          }
        case m => F.raiseError(InvalidMessage(s"Expected $messageId, received $m"))

      }
    def send(message: Message): F[Unit] =
      socket.send(Extended(messageId, Message.encode(message))) <*
      logger.debug(s"Request $message")
    Stream
      .range(0, 100)
      .evalMap { index =>
        send(Message.Request(index)) *> receive.flatMap {
          case Message.Data(`index`, bytes) => bytes.pure
          case _ => F.raiseError[ByteVector](InvalidMessage(""))
        }
      }
      .scan(ByteVector.empty)(_ ++ _)
      .find(_.size >= size)
      .compile
      .lastOrError
  }

  def download[F[_]](
    swarm: Swarm[F]
  )(implicit F: Concurrent[F]): F[MetaInfo] =
    swarm.connected.stream
      .evalMap(_.downloadMetadata.attempt)
      .collectFirst {
        case Right(Some(metadata)) => metadata
      }
      .evalMap { bytes =>
        F.fromEither(MetaInfo.fromBytes(bytes))
      }
      .compile
      .lastOrError

  case class InvalidMessage(message: String) extends Exception(message)
}
