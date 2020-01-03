package object environment {

  private val backendAddress = "bittorrent-server.herokuapp.com"

  def wsUrl(path: String): String = s"wss://$backendAddress$path"
  def httpUrl(path: String): String = s"https://$backendAddress$path"

//  local
//  private val backendAddress = "localhost:9999"
//
//  def wsUrl(path: String): String = s"ws://$backendAddress$path"
//  def httpUrl(path: String): String = s"http://$backendAddress$path"
}
