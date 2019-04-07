package com.github.lavrov.bittorrent

import java.time.Instant

import com.github.lavrov.bencode.Bencode
import com.github.lavrov.bencode.reader._
import cats.syntax.invariant._
import cats.syntax.apply._
import scodec.bits.ByteVector

case class MetaInfo(
    info: Info,
    announce: String,
    creationDate: Option[Instant]
)

sealed trait Info
object Info {
  case class SingleFile(
      pieceLength: Long,
      pieces: ByteVector,
      length: Long,
      md5sum: Option[ByteVector]
  ) extends Info

  case class MultipleFiles(
      pieceLength: Long,
      pieces: ByteVector,
      files: List[File]
  ) extends Info

  case class File(
      length: Long,
      path: List[String]
  )
}

object MetaInfo {

  implicit val InstantFormat: BencodeFormat[Instant] =
    BencodeFormat.LongReader.imap(Instant.ofEpochMilli)(_.toEpochMilli)

  implicit val SingleFileFormat: BencodeFormat[Info.SingleFile] =
    (
      field[Long]("piece length"),
      field[ByteVector]("pieces"),
      field[Long]("length"),
      optField[ByteVector]("md5sum")
    ).imapN(Info.SingleFile)(v => (v.pieceLength, v.pieces, v.length, v.md5sum))

  implicit val FileFormat: BencodeFormat[Info.File] =
    (
      field[Long]("length"),
      field[List[String]]("path")
    ).imapN(Info.File)(v => (v.length, v.path))

  implicit val MultipleFileFormat: BencodeFormat[Info.MultipleFiles] =
    (
      field[Long]("piece length"),
      field[ByteVector]("pieces"),
      field[List[Info.File]]("files")
    ).imapN(Info.MultipleFiles)(v => (v.pieceLength, v.pieces, v.files))

  implicit val InfoFormat: BencodeFormat[Info] =
    SingleFileFormat.upcast[Info] or MultipleFileFormat.upcast

  implicit val MetaInfoFormat: BencodeFormat[MetaInfo] =
    (
      field[Info]("info"),
      field[String]("announce"),
      optField[Instant]("creationDate")
    ).imapN(MetaInfo.apply)(v => (v.info, v.announce, v.creationDate))

  val RawInfoFormat: BencodeFormat[Bencode] = field[Bencode]("info")
}
