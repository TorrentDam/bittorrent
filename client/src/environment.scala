package object environment {

  private val config = scalajs.js.Dynamic.global.config
  private val serverUrl = config.serverUrl

  def wsUrl(path: String): String = s"wss://$serverUrl$path"
  def httpUrl(path: String): String = s"https://$serverUrl$path"
}
