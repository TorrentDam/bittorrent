package app

import slinky.core._
import slinky.core.annotations.react
import slinky.web.html._

import scala.scalajs.js
import scala.scalajs.js.annotation.{JSImport, ScalaJSDefined}

@js.native
@JSImport("app/App.css", JSImport.Default)
object AppCSS extends js.Object

@react class App extends StatelessComponent {
  type Props = Unit
  
  private val css = AppCSS

  def render() = {
    div(className := "app")(
      header(className := "app-header")(
        h1(className := "app-title")("Welcome to Bittorrent")
      ),
      p(className := "app-intro")(
        "Download and watch movies from Bittorrent network"
      )
    )
  }
}
