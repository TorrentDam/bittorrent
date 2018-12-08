package com.github.lavrov.bittorrent

import java.time.Instant

import com.github.lavrov.bencode.reader.BencodeReader
import cats.syntax.apply._
import cats.syntax.functor._
import cats.syntax.semigroupk._
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
    files: List[File]
  )
  extends Info

  case class File(
    info: SingleFileInfo,
    path: String
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
      BencodeReader.field[SingleFileInfo]("info"),
      BencodeReader.field[String]("path")
    )
    .mapN(File)

  implicit val MultipleFileInfoReader: BencodeReader[MultipleFileInfo] =
    BencodeReader.field[List[File]]("files").map(MultipleFileInfo)

  implicit val InfoReader: BencodeReader[Info] =
    SingleFileInfoReader.widen[Info] or MultipleFileInfoReader.widen

}
