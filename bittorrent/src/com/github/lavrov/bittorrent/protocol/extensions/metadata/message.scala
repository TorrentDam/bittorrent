package com.github.lavrov.bittorrent.protocol.extensions.metadata

import scodec.bits.ByteVector
import cats.syntax.all._
import com.github.lavrov.bencode
import com.github.lavrov.bencode.BencodeFormatException
import com.github.lavrov.bencode.format._

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
    bencode.encode(bc).toByteVector ++ extraBytes.getOrElse(ByteVector.empty)
  }

  def decode(bytes: ByteVector): Either[Throwable, Message] = {
    bencode
      .decodeHead(bytes.toBitVector)
      .flatMap {
        case (remainder, result) =>
          MessageFormat.read(result).map {
            case (msgType, piece) =>
              msgType match {
                case 0 => Request(piece)
                case 1 => Data(piece, remainder.toByteVector)
                case 2 => Reject(piece)
              }
          }
      }
  }
}
