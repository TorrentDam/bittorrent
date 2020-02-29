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
    case class State(cells: Map[Int, Deferred[F, ByteVector]])
    for {
      pieces <- Ref.of(State(Map.empty))
    } yield new Multiplexer[F] {
      def get(index: Int): F[ByteVector] =
        pieces.modify { pieces =>
          pieces.cells.get(index) match {
            case Some(deferred) => (pieces, deferred.get)
            case _ =>
              val deferred = Deferred.unsafe[F, ByteVector]
              val cells = pieces.cells.updated(index, deferred)
              val effect = request(index).flatMap(deferred.complete).start >> deferred.get
              (pieces.copy(cells = cells), effect)
          }
        }.flatten
    }
  }
}
