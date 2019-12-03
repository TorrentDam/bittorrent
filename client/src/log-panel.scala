import diode.Dispatcher
import slinky.core.FunctionalComponent
import slinky.core.annotations.react
import slinky.web.html._

import material_ui.core._

@react
object LogPanel {
  case class Props(model: List[String], dispatcher: Dispatcher)

  val component = FunctionalComponent[Props] { props =>
    List()(
      for { (line, index) <- props.model.zipWithIndex } yield ListItem()(
        key := s"log-$index",
        line
      )
    )

  }
}
