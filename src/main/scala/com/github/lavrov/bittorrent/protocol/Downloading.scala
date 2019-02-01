package com.github.lavrov.bittorrent.protocol

import cats.Monad
import cats.effect.{Concurrent, Sync}
import cats.effect.concurrent.MVar
import cats.syntax.all._
import com.github.lavrov.bittorrent.{Info, InfoHash, MetaInfo, PeerId}
import scodec.bits.ByteVector

class Downloading[F[_]: Concurrent](peerConnection: PeerConnection[F]) {

  def start(selfId: PeerId, infoHash: InfoHash, metaInfo: MetaInfo, connection: Connection[F]): F[Unit] = {
    for {
      handle <- peerConnection.connect(selfId, infoHash, connection)
      _ <- downloadFrom(handle, metaInfo)
    }
    yield ()
  }

  def downloadFrom(handle: peerConnection.Handle, metaInfo: MetaInfo): F[Unit] = {

    def downloadFile(fileInfo: Info.SingleFile): F[Unit] = {
      def loop(index: Long): F[Unit] = {
        val pieceLength = math.min(fileInfo.pieceLength, fileInfo.length - index * fileInfo.pieceLength)
        if (pieceLength != 0)
          downloadPiece(index, pieceLength, fileInfo.pieces.drop(index * 20).take(20)).flatMap(_ =>
            loop(index + 1))
        else
          Monad[F].unit
      }
      Sync[F].delay(println(s"Download file $fileInfo")) *>
      loop(0)
    }

    def downloadPiece(pieceIndex: Long, length: Long, checksum: ByteVector): F[Unit] = {
      val maxChunkSize = 16 * 1024
      def loop(index: Long): F[Unit] = {
        val chunkSize = math.min(maxChunkSize, length - index * maxChunkSize)
        if (chunkSize != 0) {
          val begin = index * maxChunkSize
          Sync[F].delay(println(s"Try download $pieceIndex, $begin, $chunkSize")) *>
          handle.algebra.download(pieceIndex, begin, chunkSize).flatMap { _ =>
            handle.events
              .flatMap {
                case PeerConnection.Event.Downloaded(`pieceIndex`, `begin`, bytes) =>
                  println(s"Received bytes $index, $begin, ${bytes.size}!!!")
                  loop(index + 1)
                case ev =>
                  println(s"unknown event $ev!!!")
                  Monad[F].unit
              }
          }
        }
        else
          Monad[F].unit
      }
      Sync[F].delay(println(s"Download piece $pieceIndex, $length, $checksum")) *>
      loop(0)
    }

    metaInfo.info match {
      case fileInfo: Info.SingleFile => downloadFile(fileInfo)
      case _ => ???
    }
  }

}
