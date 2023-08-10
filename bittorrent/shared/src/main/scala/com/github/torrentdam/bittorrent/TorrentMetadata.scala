package com.github.torrentdam.bittorrent

import cats.implicits.*
import com.github.torrentdam.bencode
import com.github.torrentdam.bencode.format.*
import com.github.torrentdam.bencode.Bencode
import com.github.torrentdam.bencode.BencodeFormatException
import java.time.Instant
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

  given BencodeFormat[File] =
    (
      field[Long]("length"),
      field[List[String]]("path")
    ).imapN(File.apply)(v => (v.length, v.path))

  given BencodeFormat[TorrentMetadata] = {
    def to(name: String, pieceLength: Long, pieces: ByteVector, length: Option[Long], filesOpt: Option[List[File]]) = {
      val files = length match {
        case Some(length) => List(File(length, List(name)))
        case None         => filesOpt.combineAll
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
      summon[BencodeFormat[TorrentMetadata]].read(bcode).map { metadata =>
        Lossless(metadata, bcode)
      }
    given BencodeFormat[Lossless] = {
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

  private given BencodeFormat[Instant] =
    BencodeFormat.LongFormat.imap(Instant.ofEpochMilli)(_.toEpochMilli)

  private val torrentFileFormat: BencodeFormat[TorrentFile] = {
    (
      field[TorrentMetadata.Lossless]("info"),
      fieldOptional[Instant]("creationDate")
    ).imapN(TorrentFile(_, _))(v => (v.info, v.creationDate))
  }

  def fromBencode(bcode: Bencode): Either[BencodeFormatException, TorrentFile] =
    torrentFileFormat.read(bcode)
  def fromBytes(bytes: ByteVector): Either[Throwable, TorrentFile] =
    bencode.decode(bytes.bits).flatMap(fromBencode)
}
