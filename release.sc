import mill._, scalalib._

trait ReleaseModule extends ScalaModule {
    def releaseVersion = "0.1"
    def githubRelease(authToken: String) = T.command {
        val jar = assembly()
        upload(
            jar.path,
            releaseVersion,
            "bittorrent",
            authToken
        )
        println(s"Release $jar")
    }
}

import ammonite.ops._

@main
def upload(uploadedFile: Path,
          tagName: String,
          uploadName: String,
          authKey: String): String = {
  println("upload.apply")
  println(uploadedFile)
  println(tagName)
  println(uploadName)
  println(authKey)
  val body = requests.get(
    "https://api.github.com/repos/lavrov/bittorrent/releases/tags/" + tagName,
    headers = Seq("Authorization" -> s"token $authKey"),
  )

  val parsed = ujson.read(body.text)

  println(body)

  val snapshotReleaseId = parsed("id").num.toInt


  val uploadUrl =
    s"https://uploads.github.com/repos/lavrov/bittorrent/releases/" +
      s"$snapshotReleaseId/assets?name=$uploadName"

  val res = requests.post(
    uploadUrl,
    headers = Seq(
      "Content-Type" -> "application/octet-stream",
      "Authorization" -> s"token $authKey"
    ),
    connectTimeout = 5000, 
    readTimeout = 60000,
    data = read.bytes(uploadedFile)
  ).text
   

  println(res)

  val longUrl = ujson.read(res)("browser_download_url").str

  longUrl
}
