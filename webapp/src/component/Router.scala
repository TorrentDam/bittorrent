package component

import java.net.URLDecoder

import com.github.lavrov.bittorrent.InfoHash
import monix.reactive.subjects.Var
import monix.execution.Scheduler.Implicits.global
import slinky.core.facade.{Hooks, ReactElement}
import org.scalajs.dom.window
import slinky.core.FunctionalComponent
import slinky.web.html._

trait Router {
  def current: Router.Route
  def navigate(route: Router.Route): Unit
  def onNavigate(route: Router.Route => Unit): Unit
}
object Router {
  def apply(): Router = {
    def parseHash: Option[Route] = {
      val str = window.location.hash.drop(1)
      Route.fromString(str)
    }
    val routeVar = Var[Route](parseHash.getOrElse(Route.Root))
    window.onhashchange = { _ =>
      val route = parseHash.getOrElse(Route.Root)
      routeVar := route
    }
    new Router {
      def current: Route = routeVar()
      def navigate(route: Route): Unit =
        window.location.hash = Route.toString(route)
      def onNavigate(callback: Route => Unit): Unit =
        routeVar.foreach(callback)
    }
  }

  sealed trait Route

  object Route {
    case object Root extends Route
    case class Search(query: String) extends Route
    case class Torrent(infoHash: InfoHash) extends Route
    case class File(index: Int, torrent: Torrent) extends Route

    def fromString(string: String): Option[Route] =
      PartialFunction.condOpt(decode(string)) {
        case s"search/$query" => Search(query)
        case s"torrent/${InfoHash.fromString(infoHash)}/file/${Number(index)}" => File(index, Torrent(infoHash))
        case s"torrent/${InfoHash.fromString(infoHash)}" => Torrent(infoHash)
        case _ => Root
      }

    def toString(route: Route): String =
      route match {
        case Root => ""
        case Search(query) => s"search/$query"
        case Torrent(infoHash) => s"torrent/$infoHash"
        case File(index, Torrent(infoHash)) => s"torrent/$infoHash/file/$index"
      }

    private val Number: PartialFunction[String, Int] = Function.unlift(_.toIntOption)

    private def decode(value: String) = URLDecoder.decode(value, "UTF-8")
  }
}
