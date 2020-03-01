import cats.effect.Concurrent
import cats.effect.concurrent.{Deferred, Ref}
import cats.implicits._
import cats.effect.implicits._
import scodec.bits.ByteVector

trait Multiplexer[F[_]] {
  def get(index: Int): F[ByteVector]
}

object Multiplexer {

  def apply[F[_]](request: Int => F[ByteVector])(implicit F: Concurrent[F]): F[Multiplexer[F]] = {
    for {
      pieces <- Ref.of(Map.empty[Int, Deferred[F, ByteVector]])
    } yield new Multiplexer[F] {
      def get(index: Int): F[ByteVector] =
        for {
          effect <- pieces.modify { pieces =>
            pieces.get(index) match {
              case Some(deferred) => (pieces, deferred.get)
              case _ =>
                val deferred = Deferred.unsafe[F, ByteVector]
                val updated = pieces.updated(index, deferred)
                val effect = request(index).flatMap(deferred.complete).start >> deferred.get
                (updated, effect)
            }
          }
          result <- effect
          _ <- pieces.update { pieces =>
            pieces.removed(index)
          }
        } yield result
    }
  }
}
