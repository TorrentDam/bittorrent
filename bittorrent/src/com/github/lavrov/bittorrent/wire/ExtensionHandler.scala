package com.github.lavrov.bittorrent.wire

import cats.implicits._
import cats.{Applicative, Monad, MonadError}
import cats.effect.{Concurrent, Sync}
import cats.effect.concurrent.{Deferred, MVar, Ref}
import com.github.lavrov.bittorrent.InfoHash
import com.github.lavrov.bittorrent.TorrentMetadata.Lossless
import com.github.lavrov.bittorrent.protocol.extensions.Extensions.MessageId
import com.github.lavrov.bittorrent.protocol.extensions.metadata.UtMessage
import com.github.lavrov.bittorrent.protocol.extensions.{ExtensionHandshake, Extensions}
import com.github.lavrov.bittorrent.protocol.message.Message
import fs2.Stream
import scodec.bits.ByteVector

trait ExtensionHandler[F[_]] {

  def apply(message: Message.Extended): F[Unit]
}

object ExtensionHandler {

  def noop[F[_]](implicit F: Applicative[F]): ExtensionHandler[F] = _ => F.unit

  def dynamic[F[_]: Monad](get: F[ExtensionHandler[F]]): ExtensionHandler[F] = message => get.flatMap(_(message))

  type Send[F[_]] = Message.Extended => F[Unit]

  trait InitExtension[F[_]] {

    def init: F[ExtensionApi[F]]
  }

  object InitExtension {

    def apply[F[_]](
      infoHash: InfoHash,
      send: Send[F],
      utMetadata: UtMetadata.Create[F]
    )(implicit F: Concurrent[F]): F[(ExtensionHandler[F], InitExtension[F])] =
      for {
        apiDeferred <- Deferred[F, ExtensionApi[F]]
        handlerRef <- Ref.of[F, ExtensionHandler[F]](ExtensionHandler.noop)
        _ <- handlerRef.set(
          {
            case Message.Extended(MessageId.Handshake, payload) =>
              for {
                handshake <- F.fromEither(ExtensionHandshake.decode(payload))
                (handler, extensionApi) <- ExtensionApi[F](infoHash, send, utMetadata, handshake)
                _ <- handlerRef.set(handler)
                _ <- apiDeferred.complete(extensionApi)
              } yield ()
            case message =>
              F.raiseError(InvalidMessage(s"Expected Handshake but received ${message.getClass.getSimpleName}"))
          }
        )
      } yield {

        val handler = dynamic(handlerRef.get)

        val api = new InitExtension[F] {

          def init: F[ExtensionApi[F]] = {
            val message =
              Message.Extended(
                MessageId.Handshake,
                ExtensionHandshake.encode(Extensions.handshake)
              )
            send(message) >> apiDeferred.get
          }
        }

        (handler, api)
      }
  }

  trait ExtensionApi[F[_]] {

    def utMetadata: Option[UtMetadata[F]]
  }

  object ExtensionApi {

    def apply[F[_]](
      infoHash: InfoHash,
      send: Send[F],
      utMetadata: UtMetadata.Create[F],
      handshake: ExtensionHandshake
    )(implicit F: MonadError[F, Throwable]): F[(ExtensionHandler[F], ExtensionApi[F])] = {
      for {
        (utHandler, utMetadata0) <- utMetadata(infoHash, handshake, send)
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

    def fetch: F[Lossless]
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
        infoHash: InfoHash,
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

              (receiveQueue.put _, (new Impl(sendUtMessage, receiveUtMessage, size, infoHash)).some)
            }

          case None =>
            (Handler.unit[F], Option.empty[UtMetadata[F]]).pure[F]

        }

      }
    }

    private class Impl[F[_]](
      send: UtMessage => F[Unit],
      receive: F[UtMessage],
      size: Long,
      infoHash: InfoHash
    )(implicit F: Sync[F])
        extends UtMetadata[F] {

      def fetch: F[Lossless] =
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
          .ensure(InvalidMetadata()) { metadata =>
            metadata.digest("SHA-1") == infoHash.bytes
          }
          .flatMap { bytes =>
            Lossless.fromBytes(bytes).liftTo[F]
          }
    }
  }

  case class InvalidMessage(message: String) extends Throwable(message)
  case class InvalidMetadata() extends Throwable
}
