package com.github.lavrov.bittorrent.wire

import cats.implicits._
import cats.{Applicative, MonadError}
import cats.effect.{Concurrent, Sync}
import cats.effect.concurrent.{Deferred, MVar, TryableDeferred}
import com.github.lavrov.bittorrent.protocol.extensions.metadata.UtMessage
import com.github.lavrov.bittorrent.protocol.extensions.{ExtensionHandshake, Extensions}
import com.github.lavrov.bittorrent.protocol.message.Message
import fs2.Stream
import scodec.bits.ByteVector

trait ExtensionHandler[F[_]] {

  def apply(message: Message.Extended): F[Unit]
}

object ExtensionHandler {

  type Send[F[_]] = Message.Extended => F[Unit]

  class Api[F[_]](
    send: Send[F],
    next: ExtensionHandshake => F[(ExtensionHandler[F], ExtensionApi[F])],
    handshakeDeferred: Deferred[F, ExtensionHandshake],
    nextDeferred: TryableDeferred[F, ExtensionHandler[F]]
  )(implicit F: MonadError[F, Throwable]) {

    def init: F[ExtensionApi[F]] =
      for {
        _ <- send(Extensions.handshake)
        handshake <- handshakeDeferred.get
        (handler, extensions) <- next(handshake)
        _ <- nextDeferred.complete(handler)
      } yield extensions
  }

  object Api {

    def apply[F[_]](
      send: Send[F],
      utMetadata: UtMetadata.Create[F]
    )(implicit F: Concurrent[F]): F[(ExtensionHandler[F], Api[F])] =
      for {
        handshakeDeferred <- Deferred[F, ExtensionHandshake]
        nextDeferred <- Deferred.tryable[F, ExtensionHandler[F]]
      } yield {

        val handler: ExtensionHandler[F] = {
          case Message.Extended(Extensions.MessageId.Handshake, payload) =>
            F.fromEither(ExtensionHandshake.decode(payload)) >>= handshakeDeferred.complete
          case message =>
            nextDeferred.tryGet.flatMap {
              case Some(handler) => handler(message)
              case None => F.unit
            }
        }

        val api = new Api(send, ExtensionApi[F](send, utMetadata, _), handshakeDeferred, nextDeferred)

        (handler, api)
      }
  }

  trait ExtensionApi[F[_]] {

    def utMetadata: Option[UtMetadata[F]]
  }

  object ExtensionApi {

    def apply[F[_]](
      send: Send[F],
      utMetadata: UtMetadata.Create[F],
      handshake: ExtensionHandshake
    )(implicit F: MonadError[F, Throwable]): F[(ExtensionHandler[F], ExtensionApi[F])] = {
      for {
        (utHandler, utMetadata0) <- utMetadata(handshake, send)
      } yield {

        val handler: ExtensionHandler[F] = {
          case Message.Extended(Extensions.MessageId.Metadata, messageBytes) =>
            F.fromEither(UtMessage.decode(messageBytes)) >>= utHandler.apply
          case Message.Extended(id, _) =>
            F.raiseError(InvalidMessage(s"Unsupported message id=$id"))
        }

        val api: ExtensionApi[F] = new ExtensionApi[F] {
          def utMetadata: Option[UtMetadata[F]] = utMetadata0
        }

        (handler, api)
      }

    }

  }

  trait UtMetadata[F[_]] {

    def fetch: F[ByteVector]
  }

  object UtMetadata {

    trait Handler[F[_]] {

      def apply(message: UtMessage): F[Unit]
    }

    object Handler {

      def unit[F[_]](implicit F: Applicative[F]): Handler[F] = _ => F.unit
    }

    class Create[F[_]](implicit F: Concurrent[F]) {

      def apply(
        handshake: ExtensionHandshake,
        send: Message.Extended => F[Unit]
      ): F[(Handler[F], Option[UtMetadata[F]])] = {

        (handshake.extensions.get("ut_metadata"), handshake.metadataSize).tupled match {
          case Some((messageId, size)) =>
            for {
              receiveQueue <- MVar.empty[F, UtMessage]
            } yield {
              def sendUtMessage(utMessage: UtMessage) = {
                val message = Message.Extended(messageId, UtMessage.encode(utMessage))
                send(message)
              }

              def receiveUtMessage: F[UtMessage] = receiveQueue.take

              (receiveQueue.put _, (new Impl(sendUtMessage, receiveUtMessage, size)).some)
            }

          case None =>
            (Handler.unit[F], Option.empty[UtMetadata[F]]).pure[F]

        }

      }
    }

    private class Impl[F[_]](
      send: UtMessage => F[Unit],
      receive: F[UtMessage],
      size: Long
    )(implicit F: Sync[F])
        extends UtMetadata[F] {

      def fetch: F[ByteVector] =
        Stream
          .range(0, 100)
          .evalMap { index =>
            send(UtMessage.Request(index)) *> receive.flatMap {
              case UtMessage.Data(`index`, bytes) => bytes.pure[F]
              case m =>
                F.raiseError[ByteVector](
                  InvalidMessage(s"Data message expected but received ${m.getClass.getSimpleName}")
                )
            }
          }
          .scan(ByteVector.empty)(_ ++ _)
          .find(_.size >= size)
          .compile
          .lastOrError
    }
  }

  case class InvalidMessage(message: String) extends Exception(message)
}
