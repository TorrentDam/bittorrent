package com.github.torrentdam.bittorrent

import cats.effect.std.Random
import cats.syntax.all.*
import cats.Monad
import scodec.bits.ByteVector

final case class PeerId(bytes: ByteVector) {
  override def toString() = s"PeerId(${bytes.decodeUtf8.getOrElse(bytes.toHex)})"
}

object PeerId {

  def apply(b0: Byte, b1: Byte, b2: Byte, b3: Byte, b4: Byte, b5: Byte): PeerId = {
    val bytes = Array[Byte](b0, b1, b2, b3, b4, b5)
    val hexPart = ByteVector(bytes).toHex
    new PeerId(ByteVector.encodeUtf8("-qB0000-" + hexPart).toOption.get)
  }

  private def apply(bytes: Array[Byte]): PeerId = {
    val hexPart = ByteVector(bytes).toHex
    new PeerId(ByteVector.encodeUtf8("-qB0000-" + hexPart).toOption.get)
  }

  def generate[F[_]](using Random[F], Monad[F]): F[PeerId] =
    for bytes <- Random[F].nextBytes(6)
    yield PeerId(bytes)
}
