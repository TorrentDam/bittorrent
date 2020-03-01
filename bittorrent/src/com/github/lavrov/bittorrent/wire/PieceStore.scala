package com.github.lavrov.bittorrent.wire

import java.nio.file.{Files, Path, Paths}

import cats.effect.concurrent.Ref
import cats.implicits._
import cats.effect.{Resource, Sync}
import scodec.bits.ByteVector

import scala.collection.immutable.BitSet

trait PieceStore[F[_]] {
  def get(index: Int): F[Option[ByteVector]]
  def put(index: Int, bytes: ByteVector): F[Unit]
}

object PieceStore {
  def disk[F[_]](directory: Path)(implicit F: Sync[F]): Resource[F, PieceStore[F]] = {

    val createDirectory = F.delay {
      Files.createDirectories(directory)
    }

    val deleteDirectory = F.delay {
      Files.delete(directory)
    }

    Resource.make(createDirectory)(_ => deleteDirectory).evalMap { directory =>
      for {
        availability <- Ref.of(BitSet.empty)
      } yield new Impl(directory, availability)
    }
  }

  private class Impl[F[_]](directory: Path, availability: Ref[F, BitSet])(implicit F: Sync[F]) extends PieceStore[F] {

    def get(index: Int): F[Option[ByteVector]] =
      for {
        availability <- availability.get
        available = availability(index)
        result <- if (available) readFile(pieceFile(index)).map(_.some) else none[ByteVector].pure[F]
      } yield result

    def put(index: Int, bytes: ByteVector): F[Unit] =
      for {
        _ <- F.delay {
          Files.write(pieceFile(index), bytes.toArray)
        }
        _ <- availability.update(_ + index)
      } yield ()

    private def pieceFile(index: Int) = directory.resolve(index.toString)

    private def readFile(file: Path) = F.delay {
      val byteArray = Files.readAllBytes(file)
      ByteVector(byteArray)
    }
  }
}
