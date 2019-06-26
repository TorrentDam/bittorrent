package com.github.lavrov.bittorrent.protocol

import com.github.lavrov.bittorrent.InfoHash
import java.nio.channels.AsynchronousChannelGroup
import scodec.bits.ByteVector
import cats.syntax.all._
import cats.effect.Resource
import cats.effect.Concurrent

import fs2.Stream
import com.github.lavrov.bittorrent.PeerInfo
import com.github.lavrov.bittorrent.protocol.extensions.metadata.Message
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import scala.concurrent.duration.FiniteDuration
import cats.Monad
import com.github.lavrov.bittorrent.protocol.message.Message.Extended
import cats.effect.Timer
import scala.io.Codec
import com.github.lavrov.bittorrent.protocol.extensions.ExtensionHandshake
import com.github.lavrov.bittorrent.protocol.extensions.Extensions

import scala.concurrent.duration._
import com.github.lavrov.bencode.BencodeCodec
import scodec.Err
import io.chrisdavenport.log4cats.Logger

object DownloadTorrentMetadata {

  def start[F[_]](
      infoHash: InfoHash,
      connections: Stream[F, (PeerInfo, Resource[F, Connection0[F]])]
  )(
      implicit F: Concurrent[F],
      timer: Timer[F]
  ): F[ByteVector] =
    for {
      logger <- Slf4jLogger.fromClass(getClass())
      result <- connections
        .parEvalMapUnordered(10) {
          case (peer, connectionResource) =>
            logger.debug(s"Connecting to $peer") *>
              connectionResource.use { connection =>
                val ops = new ExtendedProtocolHandshakeOps(connection, logger)
                for {
                  _ <- ops.request(Extensions.handshakePayload)
                  metadataExtensionInfo <- ops.receive(10.seconds) {
                    case ExtensionHandshake(extensions, metadataSize) =>
                      (extensions.get("ut_metadata"), metadataSize).pure
                  }
                  metadataBytes <- metadataExtensionInfo match {
                    case (Some(messageId), Some(metadataSize)) =>
                      val ops = new TorrentMetadataExtensionOps(connection, messageId, logger)
                      downloadFlow(ops, infoHash, metadataSize).compile.last
                    case _ =>
                      none[ByteVector].pure
                  }
                } yield metadataBytes
              }.attempt
        }
        .collect {
          case Right(Some(metadata)) => metadata
        }
        .head
        .compile
        .lastOrError
    } yield result

  private def downloadFlow[F[_]](
      ops: ExtendedProtocolOps[F, Message],
      infoHash: InfoHash,
      metadataSize: Long
  )(implicit F: Monad[F]) = {
    Stream
      .range(0, 100)
      .evalMap(
        index =>
          ops.request(Message.Request(index)) *>
            ops.receive(10.seconds) {
              case Message.Data(`index`, bytes) => bytes.pure
            }
      )
      .scan(ByteVector.empty)(_ ++ _)
      .filter(_.size >= metadataSize)
      .head
      .find(_.digest("SHA-1") == infoHash.bytes)
  }

  object Error {
    case class BencodeError(err: Err) extends Error(err.messageWithContext)
    case class HandshakeFormatError(message: String, cause: Throwable) extends Error(message, cause)
  }

  class ExtendedProtocolOps[F[_], M](
      connection: Connection0[F],
      localProtocolId: Long,
      peerProtocolId: Long,
      encode: M => ByteVector,
      decode: ByteVector => Either[Throwable, M],
      logger: Logger[F]
  )(
      implicit F: Concurrent[F],
      timer: Timer[F]
  ) {
    def receive[A](timeout: FiniteDuration)(f: PartialFunction[M, F[A]]): F[A] = {
      def recur: F[A] = {
        connection.receive
          .flatMap {
            case em @ Extended(`localProtocolId`, messageBytes) =>
              F.fromEither(decode(messageBytes))
                .flatMap(
                  message =>
                    logger.debug(s"Received $message") *> (
                      if (f.isDefinedAt(message)) f(message)
                      else recur
                    )
                )
            case _ => recur
          }
      }
      Concurrent.timeout(recur, timeout)
    }
    def request(message: M): F[Unit] = {
      logger.debug(s"Request $message") *>
        connection.send(
          com.github.lavrov.bittorrent.protocol.message.Message
            .Extended(peerProtocolId, encode(message))
        )
    }
  }

  class ExtendedProtocolHandshakeOps[F[_]: Concurrent: Timer](
      connection: Connection0[F],
      logger: Logger[F]
  ) extends ExtendedProtocolOps(
        connection,
        Extensions.MessageId.Handshake,
        Extensions.MessageId.Handshake,
        ExtensionHandshake.encode,
        ExtensionHandshake.decode,
        logger
      )

  class TorrentMetadataExtensionOps[F[_]: Concurrent: Timer](
      connection: Connection0[F],
      peerExtensionMessageId: Long,
      logger: Logger[F]
  ) extends ExtendedProtocolOps(
        connection,
        Extensions.MessageId.Metadata,
        peerExtensionMessageId,
        Message.encode,
        Message.decode,
        logger
      )
}
