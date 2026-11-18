package com.github.torrentdam.bittorrent.android

import com.github.torrentdam.bittorrent.MagnetLink
import munit.FunSuite

class AndroidSmokeTest extends FunSuite {
  private val sample = "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10&dn=Sintel"

  test("bittorrent library parses a magnet link on the app classpath") {
    val magnet = MagnetLink.fromString(sample)
    assert(magnet.isDefined, "magnet link should parse")
    assertEquals(magnet.get.infoHash.toHex, "08ada5a7a6183aae1e09d831df6748d566095a10")
    assertEquals(magnet.get.displayName, Some("Sintel"))
    assertEquals(magnet.get.trackers, Nil)
  }
}
