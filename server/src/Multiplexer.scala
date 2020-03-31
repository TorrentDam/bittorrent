import cats.effect.Concurrent
import cats.effect.concurrent.{Deferred, Ref}
import cats.implicits._
import cats.effect.implicits._
import fs2.Stream

trait Multiplexer[F[_]] {
  def get(index: Int): F[Multiplexer.Result[F]]
}

object Multiplexer {

  type Result[F[_]] = Stream[F, Byte]

  def apply[F[_]](request: Int => F[Stream[F, Byte]])(implicit F: Concurrent[F]): F[Multiplexer[F]] = {
    for {
      pieces <- Ref.of(Map.empty[Int, Deferred[F, Either[Throwable, Result[F]]]])
    } yield new Multiplexer[F] {
      def get(index: Int): F[Stream[F, Byte]] = {
        val cleanup =
          pieces.update { pieces =>
            pieces.removed(index)
          }
        for {
          effect <- pieces.modify { pieces =>
            pieces.get(index) match {
              case Some(deferred) => (pieces, deferred.get)
              case _ =>
                val deferred = Deferred.unsafe[F, Either[Throwable, Result[F]]]
                val updated = pieces.updated(index, deferred)
                val effect =
                  request(index).attempt
                    .flatTap(deferred.complete)
                    .guarantee(cleanup)
                (updated, effect)
            }
          }
          result <- effect
          result <- F.fromEither(result)
        } yield result
      }
    }
  }
}
