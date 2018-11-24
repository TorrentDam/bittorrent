package com.github.lavrov.bittorrent

import java.time.Instant

import com.github.lavrov.bittorrent.decoder.BencodeDecoder
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
  implicit val InstantDecoder: BencodeDecoder[Instant] = BencodeDecoder.LongDecoder.map(Instant.ofEpochMilli)
  implicit val MetaInfoDecoder: BencodeDecoder[MetaInfo] =
    (
      BencodeDecoder.field[Info]("info"),
      BencodeDecoder.field[String]("announce"),
      BencodeDecoder.optField[Instant]("creationDate")
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

  implicit val SingleFileInfoDecoder: BencodeDecoder[SingleFileInfo] =
    (
      BencodeDecoder.field[Long]("piece length"),
      BencodeDecoder.field[ByteVector]("pieces"),
      BencodeDecoder.field[Long]("length"),
      BencodeDecoder.optField[ByteVector]("md5sum")
    )
    .mapN(SingleFileInfo)

  implicit val FileDecoder: BencodeDecoder[File] =
    (
      BencodeDecoder.field[SingleFileInfo]("info"),
      BencodeDecoder.field[String]("path")
    )
    .mapN(File)

  implicit val MultipleFileInfoDecoder: BencodeDecoder[MultipleFileInfo] =
    BencodeDecoder.field[List[File]]("files").map(MultipleFileInfo)

  implicit val InfoDecoder: BencodeDecoder[Info] =
    SingleFileInfoDecoder.widen[Info] <+> MultipleFileInfoDecoder.widen

}
