package com.github.lavrov.bittorrent.protocol.extensions.metadata

import scodec.bits.ByteVector
import cats.syntax.all._
import com.github.lavrov.bencode.{BencodeCodec, BencodeFormatException}
import com.github.lavrov.bencode.reader._

sealed trait Message

object Message {
  case class Request(piece: Long) extends Message
  case class Data(piece: Long, byteVector: ByteVector) extends Message
  case class Reject(piece: Long) extends Message

  val MessageFormat: BencodeFormat[(Long, Long)] =
    (
      field[Long]("msg_type"),
      field[Long]("piece")
    ).tupled

  def encode(message: Message): ByteVector = {
    val (bc, extraBytes) =
      message match {
        case Request(piece) => (MessageFormat.write((0, piece)).right.get, none)
        case Data(piece, bytes) => (MessageFormat.write((1, piece)).right.get, bytes.some)
        case Reject(piece) => (MessageFormat.write((2, piece)).right.get, none)
      }
    BencodeCodec.instance.encode(bc).require.toByteVector ++ extraBytes.getOrElse(ByteVector.empty)
  }

  def decode(bytes: ByteVector): Either[BencodeFormatException, Message] = {
    val result = BencodeCodec.instance.decode(bytes.toBitVector).require
    MessageFormat.read(result.value).map {
      case (msgType, piece) =>
        msgType match {
          case 0 => Request(piece)
          case 1 => Data(piece, result.remainder.toByteVector)
          case 2 => Reject(piece)
        }
    }
  }
}
