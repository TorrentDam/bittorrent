package com.github.torrentdam.bittorrent.android

import android.app.Activity
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.widget.TextView
import cats.effect.unsafe.implicits.global
import com.github.torrentdam.bittorrent.MagnetLink

class MainActivity extends Activity {
  private val Tag = "TorrentDam"

  override def onCreate(savedInstanceState: Bundle): Unit = {
    super.onCreate(savedInstanceState)
    val textView = TextView(this)
    setContentView(textView)

    (Option(getIntent.getStringExtra("infoHash")), Option(getIntent.getStringExtra("peer"))) match {
      case (Some(infoHash), Some(peer)) =>
        textView.setText(s"${getString(R.string.app_name)}\ndownloading $infoHash from $peer ...")
        startDownload(infoHash, peer, textView)
      case _ =>
        // default: prove the bittorrent library (dexed into the APK) works on Android
        val sample = "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10&dn=Sintel"
        val text = MagnetLink.fromString(sample) match {
          case Some(magnet) =>
            val name = magnet.displayName.getOrElse("<unnamed>")
            s"${getString(R.string.app_name)}\nmagnet: $name\ninfo-hash: ${magnet.infoHash.toHex}"
          case None => "failed to parse sample magnet link"
        }
        textView.setText(text)
    }
  }

  private def startDownload(infoHash: String, peer: String, textView: TextView): Unit = {
    val saveDir = new java.io.File(getFilesDir, "downloads").getAbsolutePath
    val powerManager = getSystemService(classOf[PowerManager])
    val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TorrentDam:download")
    wakeLock.acquire(10 * 60 * 1000L)
    new Thread(() => {
      try {
        val result = Downloader.download(infoHash, peer, saveDir).unsafeRunSync()
        Log.i(Tag, s"DOWNLOAD RESULT $result")
        runOnUiThread { () =>
          textView.setText(s"${getString(R.string.app_name)}\ndownloaded: $result")
        }
      } catch {
        case e: Throwable =>
          Log.e(Tag, s"DOWNLOAD FAILED: $e", e)
          runOnUiThread { () =>
            textView.setText(s"${getString(R.string.app_name)}\ndownload FAILED: $e")
          }
      } finally wakeLock.release()
    }).start()
  }
}
