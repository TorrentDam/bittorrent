package com.github.lavrov.bittorrent

import java.time.Instant

import com.github.lavrov.bencode
import com.github.lavrov.bencode.Bencode
import com.github.lavrov.bencode.format._
import cats.syntax.invariant._
import cats.syntax.apply._
import com.github.lavrov.bittorrent.TorrentMetadata.Info
import scodec.bits.ByteVector

case class TorrentMetadata(
  info: Info,
  creationDate: Option[Instant]
)

object TorrentMetadata {

  sealed trait Info
  object Info {
    case class SingleFile(
      name: String,
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

  implicit val InstantFormat: BencodeFormat[Instant] =
    BencodeFormat.LongReader.imap(Instant.ofEpochMilli)(_.toEpochMilli)

  implicit val SingleFileFormat: BencodeFormat[Info.SingleFile] =
    (
      field[String]("name"),
      field[Long]("piece length"),
      field[ByteVector]("pieces"),
      field[Long]("length"),
      fieldOptional[ByteVector]("md5sum")
    ).imapN(Info.SingleFile)(v => (v.name, v.pieceLength, v.pieces, v.length, v.md5sum))

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

  implicit val TorrentMetadataFormatLossless: BencodeFormat[(Bencode, Option[Instant])] =
    (
      field[Bencode]("info"),
      fieldOptional[Instant]("creationDate")
    ).tupled

  val RawInfoFormat: BencodeFormat[Bencode] = field[Bencode]("info")
}

case class MetaInfo private (
  parsed: TorrentMetadata.Info,
  raw: Bencode
)

object MetaInfo {
  def fromBytes(bytes: ByteVector): Option[MetaInfo] =
    bencode.decode(bytes.bits).toOption.flatMap(fromBencode)

  def fromBencode(bcode: Bencode): Option[MetaInfo] =
    TorrentMetadata.InfoFormat.read(bcode).toOption.map { info =>
      MetaInfo(info, bcode)
    }
}
