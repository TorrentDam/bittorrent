import scala.scalajs.js
import scala.scalajs.js.annotation.{JSExportTopLevel, JSImport}
import scala.scalajs.LinkingInfo

import slinky.core._
import slinky.web.ReactDOM
import slinky.hot

import org.scalajs.dom

import app.App

@js.native
@JSImport("Main.css", JSImport.Default)
object IndexCSS extends js.Object

object Main {
  val css = IndexCSS

  def main(args: Array[String]): Unit = {
    val container = dom.document.getElementById("root")
    ReactDOM.render(App(), container)
  }
}