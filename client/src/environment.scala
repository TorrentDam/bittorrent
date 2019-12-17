object Environment {

  private val backendAddress = "bittorrent-server.herokuapp.com"

  def wsUrl(path: String): String = s"wss://$backendAddress$path"
  def httpUrl(path: String): String = s"https://$backendAddress$path"
}
