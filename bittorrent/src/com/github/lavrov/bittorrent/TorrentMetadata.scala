package com.github.lavrov.bittorrent

import java.time.Instant

import com.github.lavrov.bencode
import com.github.lavrov.bencode.{Bencode, BencodeFormatException}
import com.github.lavrov.bencode.format._
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
    BencodeFormat.LongFormat.imap(Instant.ofEpochMilli)(_.toEpochMilli)

  implicit val TorrentFileFormat: BencodeFormat[TorrentFile] = {
    (
      field[MetaInfo]("info"),
      fieldOptional[Instant]("creationDate")
    ).imapN(TorrentFile(_, _))(v => (v.info, v.creationDate))
  }
}
