package com.github.lavrov.bittorrent.protocol.extensions

import cats.MonadError
import cats.syntax.all._
import com.github.lavrov.bencode.{Bencode, BencodeCodec}
import com.github.lavrov.bittorrent.protocol.message.Message
import io.chrisdavenport.log4cats.Logger
import scodec.Err
import scodec.bits.BitVector

object Extensions {

  object MessageId {
    val Handshake = 0L
    val Metadata = 1L
  }

  def handshake: Message.Extended =
    Message.Extended(
      MessageId.Handshake,
      BencodeCodec.instance
        .encode(
          ExtensionHandshake.Format
            .write(
              ExtensionHandshake(
                Map(
                  ("ut_metadata", MessageId.Metadata)
                ),
                None
              )
            )
            .right.get
        )
        .require
        .toByteVector
    )

  def processHandshake[F[_]](payload: BitVector, logger: Logger[F])(
      implicit F: MonadError[F, Throwable]
  ): F[ExtensionHandshake] =
    for {
      bc <- F.fromEither(
        BencodeCodec.instance
          .decodeValue(payload)
          .toEither
          .leftMap(Error.BencodeError)
      )
      _ <- logger.debug(s"Extension protocol handshake response: $bc")
      handshakeResponse <- F.fromEither(
        ExtensionHandshake.Format
          .read(bc)
          .leftMap(e => Error.HandshakeFormatError("Unable to parse handshake response", e))
      )
    } yield handshakeResponse

  sealed class Error(message: String = null, cause: Throwable = null)
      extends Throwable(message, cause)
  object Error {
    case class BencodeError(err: Err) extends Error(err.messageWithContext)
    case class HandshakeFormatError(message: String, cause: Throwable) extends Error(message, cause)
  }
}

sealed trait ProtocolExtension

object ProtocolExtension {
  case object Metadata extends ProtocolExtension
}
