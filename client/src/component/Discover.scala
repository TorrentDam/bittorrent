package component

import slinky.core.FunctionalComponent
import slinky.core.annotations.react
import slinky.web.html._

@react
object Discover {
  case class Props()

  val component = FunctionalComponent[Props] { _ =>
    div("WIP")
  }
}
