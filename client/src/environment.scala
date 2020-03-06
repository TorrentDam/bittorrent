package object environment {

  private val backendAddress = "25e5f366-a664-487a-8bb2-33f106743c8a.pub.cloud.scaleway.com"

  def wsUrl(path: String): String = s"wss://$backendAddress$path"
  def httpUrl(path: String): String = s"https://$backendAddress$path"

//  local
//  private val backendAddress = "localhost:9999"
//
//  def wsUrl(path: String): String = s"ws://$backendAddress$path"
//  def httpUrl(path: String): String = s"http://$backendAddress$path"
}
