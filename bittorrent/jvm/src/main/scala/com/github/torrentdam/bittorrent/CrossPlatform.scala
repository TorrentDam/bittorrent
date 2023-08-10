package com.github.torrentdam.bittorrent

import scodec.bits.ByteVector

object CrossPlatform {
  def sha1(bytes: ByteVector): ByteVector = bytes.sha1
}