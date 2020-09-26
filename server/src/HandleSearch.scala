import org.http4s.{MediaType, Response}
import cats.effect._
import org.http4s.headers.`Content-Type`

trait HandleSearch {
  def apply(query: String): IO[Response[IO]]
}

object HandleSearch {

  val dsl = org.http4s.dsl.io
  import dsl._

  def apply(index: TorrentIndex): HandleSearch = {
    new HandleSearch {
      def apply(query: String): IO[Response[IO]] = {
        val entries = index.byName(query)
        Ok(upickle.default.write(entries))
          .map(_.withContentType(`Content-Type`(MediaType.application.json)))
      }
    }
  }
}
