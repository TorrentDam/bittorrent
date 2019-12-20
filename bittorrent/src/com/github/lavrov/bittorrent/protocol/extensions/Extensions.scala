package com.github.lavrov.bittorrent.protocol.extensions

import cats.MonadError
import cats.syntax.all._
import com.github.lavrov.bencode
import com.github.lavrov.bencode.BencodeCodec
import com.github.lavrov.bittorrent.protocol.message.Message
import scodec.Err
import scodec.bits.BitVector
import logstage.LogIO

object Extensions {

  object MessageId {
    val Handshake = 0L
    val Metadata = 1L
  }

  def handshakePayload =
    ExtensionHandshake(
      Map(
        ("ut_metadata", MessageId.Metadata)
      ),
      None
    )

  def handshake: Message.Extended =
    Message.Extended(
      MessageId.Handshake,
      bencode
        .encode(
          ExtensionHandshake.Format.write(handshakePayload).toOption.get
        )
        .toByteVector
    )

  def processHandshake[F[_]](payload: BitVector, logger: LogIO[F])(
    implicit F: MonadError[F, Throwable]
  ): F[ExtensionHandshake] =
    for {
      bc <- F.fromEither(
        bencode.decode(payload).leftMap(Error.BencodeError)
      )
      _ <- logger.debug(s"Extension protocol handshake response: $bc")
      handshakeResponse <- F.fromEither(
        ExtensionHandshake.Format
          .read(bc)
          .leftMap(e => Error.HandshakeFormatError("Unable to parse handshake response", e))
      )
    } yield handshakeResponse

  sealed class Error(message: String = null, cause: Throwable = null) extends Throwable(message, cause)
  object Error {
    case class BencodeError(cause: Throwable) extends Error(cause = cause)
    case class HandshakeFormatError(message: String, cause: Throwable) extends Error(message, cause)
  }
}

sealed trait ProtocolExtension

object ProtocolExtension {
  case object Metadata extends ProtocolExtension
}
