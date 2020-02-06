package component

import frp.{Observable, Var}
import slinky.core.facade.{Hooks, ReactElement}
import org.scalajs.dom.window
import slinky.core.FunctionalComponent

trait Router {
  def current: Router.Route
  def navigate(route: Router.Route): Unit
  def onNavigate(route: Router.Route => Unit): Unit
  def when(matchRoute: PartialFunction[Router.Route, ReactElement]): ReactElement
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
      if (route != routeVar.value) routeVar.set(route)
    }
    new Router {
      def current: Route = routeVar.value
      def navigate(route: Route): Unit =
        window.location.hash = Route.toString(route)
      def onNavigate(callback: Route => Unit): Unit =
        routeVar.subscribe(callback)
      def when(matchRoute: PartialFunction[Route, ReactElement]): ReactElement =
        component(routeVar.zoomTo(matchRoute.lift))
    }
  }

  private val component = FunctionalComponent[Observable[Option[ReactElement]]] { childVar =>
    val (child, setChild) = Hooks.useState(childVar.value)
    def subscribe() = {
      childVar.subscribe { newChild =>
        if (child != newChild) setChild(newChild)
      }
    }
    Hooks.useEffect(subscribe)
    child
  }

  sealed trait Route

  object Route {
    case object Root extends Route
    case class Torrent(infoHash: String) extends Route
    case class File(index: Int, torrent: Torrent) extends Route

    def fromString(string: String): Option[Route] = PartialFunction.condOpt(string) {
      case "" => Root
      case s"torrent/$infoHash/file/${Number(index)}" => File(index, Torrent(infoHash))
      case s"torrent/$infoHash" => Torrent(infoHash)
    }

    def toString(route: Route): String = route match {
      case Root => ""
      case Torrent(infoHash) => s"torrent/$infoHash"
      case File(index, Torrent(infoHash)) => s"torrent/$infoHash/file/$index"
    }

    private val Number: PartialFunction[String, Int] = Function.unlift(_.toIntOption)
  }
}
