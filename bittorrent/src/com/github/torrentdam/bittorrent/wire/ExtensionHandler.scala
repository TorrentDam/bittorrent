package com.github.torrentdam.bittorrent.wire

import cats.effect.kernel.Deferred
import cats.effect.kernel.Ref
import cats.effect.std.Queue
import cats.effect.IO
import cats.implicits.*
import com.github.torrentdam.bittorrent.protocol.extensions.metadata.UtMessage
import com.github.torrentdam.bittorrent.protocol.extensions.pex.PexFlags
import com.github.torrentdam.bittorrent.protocol.extensions.pex.PexMessage
import com.github.torrentdam.bittorrent.protocol.extensions.ExtensionHandshake
import com.github.torrentdam.bittorrent.protocol.extensions.Extensions
import com.github.torrentdam.bittorrent.protocol.extensions.Extensions.MessageId
import com.github.torrentdam.bittorrent.InfoHash
import com.github.torrentdam.bittorrent.PeerInfo
import com.github.torrentdam.bittorrent.TorrentMetadata.Lossless
import com.github.torrentdam.bittorrent.protocol.message.Message
import fs2.Stream
import scodec.bits.ByteVector
import com.github.torrentdam.bittorrent.CrossPlatform
import org.legogroup.woof.Logger
import org.legogroup.woof.given

trait ExtensionHandler {

  def apply(message: Message.Extended): IO[Unit]
}

object ExtensionHandler {

  val noop: ExtensionHandler = _ => IO.unit

  def dynamic(get: IO[ExtensionHandler]): ExtensionHandler = message => get.flatMap(_(message))

  type Send = Message.Extended => IO[Unit]

  trait InitExtension {

    def init: IO[ExtensionApi]
  }

  object InitExtension {

    def apply(
      infoHash: InfoHash,
      send: Send,
      utMetadata: UtMetadata.Create,
      utPex: UtPex.Create
    )(using logger: Logger[IO]): IO[(ExtensionHandler, InitExtension)] =
      for
        apiDeferred <- IO.deferred[ExtensionApi]
        handlerRef <- IO.ref[ExtensionHandler](ExtensionHandler.noop)
        _ <- handlerRef.set(
          {
            case Message.Extended(MessageId.Handshake, payload) =>
              for
                handshake <- IO.fromEither(ExtensionHandshake.decode(payload))
                (handler, extensionApi) <- ExtensionApi(infoHash, send, utMetadata, utPex, handshake)
                _ <- handlerRef.set(handler)
                _ <- apiDeferred.complete(extensionApi)
              yield ()
            case message =>
              IO.raiseError(InvalidMessage(s"Expected Handshake but received ${message.getClass.getSimpleName}"))
          }
        )
      yield

        val handler = dynamic(handlerRef.get)

        val api = new InitExtension {

          def init: IO[ExtensionApi] = {
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

  trait ExtensionApi {

    def utMetadata: Option[UtMetadata]
    def utPex: Option[UtPex]
  }

  object ExtensionApi {

    def apply(
      infoHash: InfoHash,
      send: Send,
      utMetadata: UtMetadata.Create,
      utPex: UtPex.Create,
      handshake: ExtensionHandshake
    )(using logger: Logger[IO]): IO[(ExtensionHandler, ExtensionApi)] = {
      for
        (utHandler, utMetadata0) <- utMetadata(infoHash, handshake, send)
        (pexHandler, utPex0) <- utPex(handshake, send)
      yield

        val handler: ExtensionHandler = {
          case Message.Extended(Extensions.MessageId.Metadata, messageBytes) =>
            IO.fromEither(UtMessage.decode(messageBytes)) >>= utHandler.apply
          case Message.Extended(Extensions.MessageId.Pex, messageBytes) =>
            IO.fromEither(PexMessage.decode(messageBytes)) >>= pexHandler.apply
          case Message.Extended(id, _) =>
            IO.raiseError(InvalidMessage(s"Unsupported message id=$id"))
        }

        val api: ExtensionApi = new ExtensionApi {
          def utMetadata: Option[UtMetadata] = utMetadata0
          def utPex: Option[UtPex] = utPex0
        }

        (handler, api)
      end for
    }

  }

  trait UtMetadata {

    def fetch: IO[Lossless]
  }

  object UtMetadata {

    trait Handler {

      def apply(message: UtMessage): IO[Unit]
    }

    object Handler {

      val unit: Handler = _ => IO.unit
    }

    class Create {

      def apply(
        infoHash: InfoHash,
        handshake: ExtensionHandshake,
        send: Message.Extended => IO[Unit]
      ): IO[(Handler, Option[UtMetadata])] = {

        (handshake.extensions.get("ut_metadata"), handshake.metadataSize).tupled match {
          case Some((messageId, size)) =>
            for receiveQueue <- Queue.bounded[IO, UtMessage](1)
            yield
              def sendUtMessage(utMessage: UtMessage) = {
                val message: Message.Extended = Message.Extended(messageId, UtMessage.encode(utMessage))
                send(message)
              }

              def receiveUtMessage: IO[UtMessage] = receiveQueue.take

              (receiveQueue.offer, (new Impl(sendUtMessage, receiveUtMessage, size, infoHash)).some)
            end for

          case None =>
            IO.pure((Handler.unit, Option.empty[UtMetadata]))

        }

      }
    }

    private class Impl(
      send: UtMessage => IO[Unit],
      receive: IO[UtMessage],
      size: Long,
      infoHash: InfoHash
    ) extends UtMetadata {

      def fetch: IO[Lossless] =
        Stream
          .range(0, 100)
          .evalMap { index =>
            send(UtMessage.Request(index)) *> receive.flatMap {
              case UtMessage.Data(`index`, bytes) => IO.pure(bytes)
              case m =>
                IO.raiseError[ByteVector](
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
            Lossless.fromBytes(bytes).liftTo[IO]
          }
    }
  }

  trait UtPex {

    def discovered: Stream[IO, PeerInfo]
    def update(connected: List[PeerInfo]): IO[Unit]
  }

  object UtPex:

    val Noop: UtPex = new UtPex:
      def discovered: Stream[IO, PeerInfo] = Stream.empty
      def update(connected: List[PeerInfo]): IO[Unit] = IO.unit

    trait Handler:

      def apply(message: PexMessage): IO[Unit]

    object Handler:

      val unit: Handler = _ => IO.unit

    class Create:

      def apply(
        handshake: ExtensionHandshake,
        send: Message.Extended => IO[Unit]
      )(using logger: Logger[IO]): IO[(Handler, Option[UtPex])] =
        handshake.extensions.get("ut_pex") match
          case Some(messageId) =>
            for discoveredQueue <- Queue.bounded[IO, PeerInfo](50)
            yield
              def sendPexMessage(pexMessage: PexMessage) =
                send(Message.Extended(messageId, PexMessage.encode(pexMessage)))

              val handler: Handler = message =>
                val allAdded = message.added ++ message.added6
                logger.trace(
                  s"PEX <<< added=${message.added.size} added6=${message.added6.size} " +
                  s"dropped=${message.dropped.size} dropped6=${message.dropped6.size}"
                ) >>
                allAdded.traverse_(discoveredQueue.offer)

              val impl = new UtPex:
                def discovered: Stream[IO, PeerInfo] =
                  Stream.fromQueueUnterminated(discoveredQueue)

                def update(connected: List[PeerInfo]): IO[Unit] =
                  val pexMessage = PexMessage(
                    added = connected.take(MaxPeersPerMessage),
                    addedFlags = connected.take(MaxPeersPerMessage).map(_ => PexFlags.Empty),
                    dropped = Nil,
                    added6 = Nil,
                    dropped6 = Nil
                  )
                  logger.trace(s"PEX >>> added=${pexMessage.added.size} (of ${connected.size} connected)") >>
                  sendPexMessage(pexMessage)

              (handler, Some(impl))
            end for

          case None =>
            IO.pure((Handler.unit, Option.empty[UtPex]))

    end Create

    private val MaxPeersPerMessage = 50

  end UtPex

  case class InvalidMessage(message: String) extends Throwable(message)
  case class InvalidMetadata() extends Throwable
}
