package com.github.lavrov.bittorrent

import java.time.Instant

import com.github.lavrov.bencode
import com.github.lavrov.bencode.{Bencode, BencodeFormatException}
import com.github.lavrov.bencode.format._
import cats.syntax.invariant._
import cats.syntax.apply._
import scodec.bits.ByteVector

case class TorrentMetadata(
  pieceLength: Long,
  pieces: ByteVector,
  files: List[TorrentMetadata.File]
)

object TorrentMetadata {

  case class File(
    length: Long,
    path: List[String]
  )

  implicit val SingleFileFormat: BencodeFormat[TorrentMetadata] =
    (
      field[String]("name"),
      field[Long]("piece length"),
      field[ByteVector]("pieces"),
      field[Long]("length")
    ).imapN(
      (name, pieceLength, pieces, length) =>
        TorrentMetadata(pieceLength, pieces, List(File(length, List(name))))
    )(v => ???)

  implicit val FileFormat: BencodeFormat[File] =
    (
      field[Long]("length"),
      field[List[String]]("path")
    ).imapN(File)(v => (v.length, v.path))

  implicit val MultipleFileFormat: BencodeFormat[TorrentMetadata] =
    (
      field[Long]("piece length"),
      field[ByteVector]("pieces"),
      field[List[File]]("files")
    ).imapN(TorrentMetadata.apply)(v => (v.pieceLength, v.pieces, v.files))

  implicit val TorrentMetadataFormat: BencodeFormat[TorrentMetadata] = BencodeFormat(
    read =
      BencodeReader(bcode => SingleFileFormat.read(bcode) orElse MultipleFileFormat.read(bcode)),
    write = MultipleFileFormat.write
  )
}

case class MetaInfo private (
  parsed: TorrentMetadata,
  raw: Bencode
)

object MetaInfo {
  def fromBytes(bytes: ByteVector): Either[Throwable, MetaInfo] =
    bencode.decode(bytes.bits).flatMap(fromBencode)

  def fromBencode(bcode: Bencode): Either[BencodeFormatException, MetaInfo] =
    TorrentMetadata.TorrentMetadataFormat.read(bcode).map { metadata =>
      MetaInfo(metadata, bcode)
    }
  implicit val MetaInfoFormat: BencodeFormat[MetaInfo] = {
    BencodeFormat(
      read = BencodeReader(MetaInfo.fromBencode),
      write = BencodeWriter(metadata => BencodeFormat.BencodeValueFormat.write(metadata.raw))
    )
  }
}

case class TorrentFile(
  info: MetaInfo,
  creationDate: Option[Instant]
)

object TorrentFile {

  implicit val InstantFormat: BencodeFormat[Instant] =
    BencodeFormat.LongReader.imap(Instant.ofEpochMilli)(_.toEpochMilli)

  implicit val TorrentFileFormat: BencodeFormat[TorrentFile] = {
    (
      field[MetaInfo]("info"),
      fieldOptional[Instant]("creationDate")
    ).imapN(TorrentFile(_, _))(v => (v.info, v.creationDate))
  }
}
