import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal

package object environment {

  @JSGlobal
  @js.native
  object config extends js.Object {
    def serverUrl: String = js.native
    def useEncryption: Boolean = js.native
  }

  private val (httpProtocol, websocketProtocol) = if (config.useEncryption) ("https", "wss") else ("http", "ws")

  def wsUrl(path: String): String = s"$websocketProtocol://${config.serverUrl}$path"
  def httpUrl(path: String): String = s"$httpProtocol://${config.serverUrl}$path"
}
