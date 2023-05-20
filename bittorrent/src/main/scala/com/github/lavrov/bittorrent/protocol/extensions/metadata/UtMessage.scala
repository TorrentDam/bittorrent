package com.github.lavrov.bittorrent.protocol.extensions.metadata

import cats.syntax.all.*
import com.github.torrentdam.bencode
import com.github.torrentdam.bencode.format.*
import scodec.bits.ByteVector

enum UtMessage:
  case Request(piece: Long)
  case Data(piece: Long, byteVector: ByteVector)
  case Reject(piece: Long)

object UtMessage {

  val MessageFormat: BencodeFormat[(Long, Long)] =
    (
      field[Long]("msg_type"),
      field[Long]("piece")
    ).tupled

  def encode(message: UtMessage): ByteVector = {
    val (bc, extraBytes) =
      message match {
        case Request(piece)     => (MessageFormat.write((0, piece)).toOption.get, none)
        case Data(piece, bytes) => (MessageFormat.write((1, piece)).toOption.get, bytes.some)
        case Reject(piece)      => (MessageFormat.write((2, piece)).toOption.get, none)
      }
    bencode.encode(bc).toByteVector ++ extraBytes.getOrElse(ByteVector.empty)
  }

  def decode(bytes: ByteVector): Either[Throwable, UtMessage] = {
    bencode
      .decodeHead(bytes.toBitVector)
      .flatMap { case (remainder, result) =>
        MessageFormat.read(result).map { case (msgType, piece) =>
          msgType match {
            case 0 => Request(piece)
            case 1 => Data(piece, remainder.toByteVector)
            case 2 => Reject(piece)
          }
        }
      }
  }
}
