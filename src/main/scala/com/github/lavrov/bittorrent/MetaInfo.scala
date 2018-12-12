package com.github.lavrov.bittorrent

import java.time.Instant

import com.github.lavrov.bencode.reader.BencodeReader
import cats.syntax.apply._
import cats.syntax.functor._
import cats.syntax.flatMap._
import cats.syntax.semigroupk._
import com.github.lavrov.bencode.{Bencode, encode => encodeValue}
import scodec.bits.ByteVector

case class MetaInfo(
  info: Info,
  announce: String,
  creationDate: Option[Instant]
)

object MetaInfo {
  implicit val InstantReader: BencodeReader[Instant] = BencodeReader.LongReader.map(Instant.ofEpochMilli)
  implicit val MetaInfoReader: BencodeReader[MetaInfo] =
    (
      BencodeReader.field[Info]("info"),
      BencodeReader.field[String]("announce"),
      BencodeReader.optField[Instant]("creationDate")
    )
    .mapN(MetaInfo.apply)

  val rawInfoHash: BencodeReader[Bencode] = BencodeReader.field[Bencode]("info")
}

sealed trait Info
object Info {
  case class SingleFileInfo(
    pieceLength: Long,
    pieces: ByteVector,
    length: Long,
    md5sum: Option[ByteVector]
  )
  extends Info

  case class MultipleFileInfo(
    pieceLength: Long,
    pieces: ByteVector,
    files: List[File]
  )
  extends Info

  case class File(
    length: Long,
    path: List[String]
  )

  implicit val SingleFileInfoReader: BencodeReader[SingleFileInfo] =
    (
      BencodeReader.field[Long]("piece length"),
      BencodeReader.field[ByteVector]("pieces"),
      BencodeReader.field[Long]("length"),
      BencodeReader.optField[ByteVector]("md5sum")
    )
    .mapN(SingleFileInfo)

  implicit val FileReader: BencodeReader[File] =
    (
      BencodeReader.field[Long]("length"),
      BencodeReader.field[List[String]]("path")
    )
    .mapN(File)

  implicit val MultipleFileInfoReader: BencodeReader[MultipleFileInfo] =
    (
      BencodeReader.field[Long]("piece length"),
      BencodeReader.field[ByteVector]("pieces"),
      BencodeReader.field[List[File]]("files")
    )
    .mapN(MultipleFileInfo)

  implicit val InfoReader: BencodeReader[Info] =
    SingleFileInfoReader.widen[Info] or MultipleFileInfoReader.widen

}
