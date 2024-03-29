package com.github.torrentdam.bittorrent.wire

import cats.effect.kernel.Deferred
import cats.effect.kernel.Ref
import cats.effect.std.Queue
import cats.effect.Async
import cats.effect.Concurrent
import cats.effect.Sync
import cats.implicits.*
import cats.Applicative
import cats.Monad
import cats.MonadError
import com.github.torrentdam.bittorrent.protocol.extensions.metadata.UtMessage
import com.github.torrentdam.bittorrent.protocol.extensions.ExtensionHandshake
import com.github.torrentdam.bittorrent.protocol.extensions.Extensions
import com.github.torrentdam.bittorrent.protocol.extensions.Extensions.MessageId
import com.github.torrentdam.bittorrent.InfoHash
import com.github.torrentdam.bittorrent.TorrentMetadata.Lossless
import com.github.torrentdam.bittorrent.protocol.message.Message
import fs2.Stream
import scodec.bits.ByteVector
import com.github.torrentdam.bittorrent.CrossPlatform

trait ExtensionHandler[F[_]] {

  def apply(message: Message.Extended): F[Unit]
}

object ExtensionHandler {

  def noop[F[_]](using F: Applicative[F]): ExtensionHandler[F] = _ => F.unit

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
    )(using F: Concurrent[F]): F[(ExtensionHandler[F], InitExtension[F])] =
      for
        apiDeferred <- F.deferred[ExtensionApi[F]]
        handlerRef <- F.ref[ExtensionHandler[F]](ExtensionHandler.noop)
        _ <- handlerRef.set(
          {
            case Message.Extended(MessageId.Handshake, payload) =>
              for
                handshake <- F.fromEither(ExtensionHandshake.decode(payload))
                (handler, extensionApi) <- ExtensionApi[F](infoHash, send, utMetadata, handshake)
                _ <- handlerRef.set(handler)
                _ <- apiDeferred.complete(extensionApi)
              yield ()
            case message =>
              F.raiseError(InvalidMessage(s"Expected Handshake but received ${message.getClass.getSimpleName}"))
          }
        )
      yield

        val handler = dynamic(handlerRef.get)

        val api = new InitExtension[F] {

          def init: F[ExtensionApi[F]] = {
            val message: Message.Extended =
              Message.Extended(
                MessageId.Handshake,
                ExtensionHandshake.encode(Extensions.handshake)
              )
            send(message) >> apiDeferred.get
          }
        }

        (handler, api)
      end for
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
    )(using F: MonadError[F, Throwable]): F[(ExtensionHandler[F], ExtensionApi[F])] = {
      for (utHandler, utMetadata0) <- utMetadata(infoHash, handshake, send)
      yield

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
      end for
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

      def unit[F[_]](using F: Applicative[F]): Handler[F] = _ => F.unit
    }

    class Create[F[_]](using F: Async[F]) {

      def apply(
        infoHash: InfoHash,
        handshake: ExtensionHandshake,
        send: Message.Extended => F[Unit]
      ): F[(Handler[F], Option[UtMetadata[F]])] = {

        (handshake.extensions.get("ut_metadata"), handshake.metadataSize).tupled match {
          case Some((messageId, size)) =>
            for receiveQueue <- Queue.bounded[F, UtMessage](1)
            yield
              def sendUtMessage(utMessage: UtMessage) = {
                val message: Message.Extended = Message.Extended(messageId, UtMessage.encode(utMessage))
                send(message)
              }

              def receiveUtMessage: F[UtMessage] = receiveQueue.take

              (receiveQueue.offer, (new Impl(sendUtMessage, receiveUtMessage, size, infoHash)).some)
            end for

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
    )(using F: Sync[F])
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
            CrossPlatform.sha1(metadata) == infoHash.bytes
          }
          .flatMap { bytes =>
            Lossless.fromBytes(bytes).liftTo[F]
          }
    }
  }

  case class InvalidMessage(message: String) extends Throwable(message)
  case class InvalidMetadata() extends Throwable
}
