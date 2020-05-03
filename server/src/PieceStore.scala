import java.nio.file.{Files, Path}

import cats.effect.concurrent.Ref
import cats.effect.{Blocker, ContextShift, Resource, Sync}
import cats.implicits._
import fs2.Stream
import scodec.bits.ByteVector

import scala.collection.immutable.BitSet
import scala.jdk.StreamConverters._

trait PieceStore[F[_]] {
  def get(index: Int): F[Option[Stream[F, Byte]]]
  def put(index: Int, bytes: ByteVector): F[Stream[F, Byte]]
}

object PieceStore {
  def disk[F[_]](
    directory: Path
  )(implicit F: Sync[F], cs: ContextShift[F], blocker: Blocker): Resource[F, PieceStore[F]] = {

    val createDirectory = F.delay {
      Files.createDirectories(directory)
    }

    val deleteDirectory = F.delay {
      Files.list(directory).toScala(List).foreach { path =>
        Files.delete(path)
      }
      Files.delete(directory)
    }

    Resource.make(createDirectory)(_ => deleteDirectory).evalMap { directory =>
      for {
        availability <- Ref.of(BitSet.empty)
      } yield new Impl(directory, availability)
    }
  }

  private class Impl[F[_]](directory: Path, availability: Ref[F, BitSet])(implicit
    F: Sync[F],
    contextShift: ContextShift[F],
    blocker: Blocker
  ) extends PieceStore[F] {

    def get(index: Int): F[Option[Stream[F, Byte]]] =
      for {
        availability <- availability.get
        available = availability(index)
      } yield if (available) readFile(pieceFile(index)).some else none

    def put(index: Int, bytes: ByteVector): F[Stream[F, Byte]] = {
      val file = pieceFile(index)
      for {
        _ <- F.delay {
          Files.write(file, bytes.toArray)
        }
        _ <- availability.update(_ + index)
      } yield readFile(file)
    }

    private def pieceFile(index: Int) = directory.resolve(index.toString)

    private def readFile(file: Path) = {
      fs2.io.file.readAll(file, blocker, 1024 * 1024)
    }
  }
}
