package com.github.lavrov.bittorrent

import java.time.Instant

import com.github.torrentdam.bencode
import com.github.torrentdam.bencode.{Bencode, BencodeFormatException}
import com.github.torrentdam.bencode.format._
import cats.implicits._
import scodec.bits.ByteVector

case class TorrentMetadata(
  name: String,
  pieceLength: Long,
  pieces: ByteVector,
  files: List[TorrentMetadata.File]
)

object TorrentMetadata {

  case class File(
    length: Long,
    path: List[String]
  )

  implicit val FileFormat: BencodeFormat[File] =
    (
      field[Long]("length"),
      field[List[String]]("path")
    ).imapN(File)(v => (v.length, v.path))

  implicit val TorrentMetadataFormat: BencodeFormat[TorrentMetadata] = {
    def to(name: String, pieceLength: Long, pieces: ByteVector, length: Option[Long], filesOpt: Option[List[File]]) = {
      val files = length match {
        case Some(length) => List(File(length, List(name)))
        case None => filesOpt.combineAll
      }
      TorrentMetadata(name, pieceLength, pieces, files)
    }
    def from(v: TorrentMetadata) =
      (v.name, v.pieceLength, v.pieces, Option.empty[Long], Some(v.files))
    (
      field[String]("name"),
      field[Long]("piece length"),
      field[ByteVector]("pieces"),
      fieldOptional[Long]("length"),
      fieldOptional[List[File]]("files")
    ).imapN(to)(from)
  }

  case class Lossless private (
    parsed: TorrentMetadata,
    raw: Bencode
  )

  object Lossless {
    def fromBytes(bytes: ByteVector): Either[Throwable, Lossless] =
      bencode.decode(bytes.bits).flatMap(fromBencode)

    def fromBencode(bcode: Bencode): Either[BencodeFormatException, Lossless] =
      TorrentMetadata.TorrentMetadataFormat.read(bcode).map { metadata =>
        Lossless(metadata, bcode)
      }
    implicit val MetaInfoFormat: BencodeFormat[Lossless] = {
      BencodeFormat(
        read = BencodeReader(fromBencode),
        write = BencodeWriter(metadata => BencodeFormat.BencodeValueFormat.write(metadata.raw))
      )
    }
  }
}

case class TorrentFile(
  info: TorrentMetadata.Lossless,
  creationDate: Option[Instant]
)

object TorrentFile {

  implicit val InstantFormat: BencodeFormat[Instant] =
    BencodeFormat.LongFormat.imap(Instant.ofEpochMilli)(_.toEpochMilli)

  implicit val TorrentFileFormat: BencodeFormat[TorrentFile] = {
    (
      field[TorrentMetadata.Lossless]("info"),
      fieldOptional[Instant]("creationDate")
    ).imapN(TorrentFile(_, _))(v => (v.info, v.creationDate))
  }
}
