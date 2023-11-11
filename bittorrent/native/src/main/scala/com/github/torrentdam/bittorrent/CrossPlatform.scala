package com.github.torrentdam.bittorrent

import scodec.bits.ByteVector

object CrossPlatform {
  def sha1(bytes: ByteVector): ByteVector = {
    val md = java.security.MessageDigest.getInstance("SHA-1")
    val digest = md.digest(bytes.toArray)
    md.reset()
    ByteVector(digest)
  }
}