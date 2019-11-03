package com.github.lavrov.bittorrent.wire

import cats.Monad
import cats.effect.{Concurrent, Resource, Timer}
import cats.syntax.all._
import com.github.lavrov.bittorrent.protocol.extensions.{ExtensionHandshake, Extensions}
import com.github.lavrov.bittorrent.protocol.extensions.metadata.Message
import com.github.lavrov.bittorrent.protocol.message.Message.Extended
import com.github.lavrov.bittorrent.{InfoHash, PeerInfo}
import fs2.Stream
import logstage.LogIO
import scodec.Err
import scodec.bits.ByteVector

import scala.concurrent.duration.{FiniteDuration, _}

object MetadataDownloader {

  def start[F[_]](
    infoHash: InfoHash,
    connections: Stream[F, (PeerInfo, Resource[F, MessageSocket[F]])]
  )(
    implicit F: Concurrent[F],
    timer: Timer[F],
    logger: LogIO[F]
  ): F[ByteVector] =
    for {
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
    connection: MessageSocket[F],
    localProtocolId: Long,
    peerProtocolId: Long,
    encode: M => ByteVector,
    decode: ByteVector => Either[Throwable, M],
    logger: LogIO[F]
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
    connection: MessageSocket[F],
    logger: LogIO[F]
  ) extends ExtendedProtocolOps(
        connection,
        Extensions.MessageId.Handshake,
        Extensions.MessageId.Handshake,
        ExtensionHandshake.encode,
        ExtensionHandshake.decode,
        logger
      )

  class TorrentMetadataExtensionOps[F[_]: Concurrent: Timer](
    connection: MessageSocket[F],
    peerExtensionMessageId: Long,
    logger: LogIO[F]
  ) extends ExtendedProtocolOps(
        connection,
        Extensions.MessageId.Metadata,
        peerExtensionMessageId,
        Message.encode,
        Message.decode,
        logger
      )
}
