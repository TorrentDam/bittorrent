package com.github.lavrov.whirpool

import java.time.Instant

import com.github.lavrov.whirpool.decoder.BencodeDecoder

case class MetaInfo(
  info: Info,
  announce: String,
  creationDate: Option[Instant]
)

sealed trait Info
object Info {
  case class FileInfo(
    pieceLength: Long,
    pieces: Long,
    length: Long,
    md5sum: String
  )
  extends Info

  case class Multiple(
    files: List[File]
  )
  extends Info

  case class File(
    info: FileInfo,
    path: String
  )

  import cats.syntax.apply._

  implicit val FileInfoDecoder: BencodeDecoder[FileInfo] =
    (
      BencodeDecoder.field[Long]("pieceLength"),
      BencodeDecoder.field[Long]("pieces"),
      BencodeDecoder.field[Long]("length"),
      BencodeDecoder.field[String]("md5sum")
    )
    .mapN(FileInfo)
}
