package com.github.lavrov.bittorrent

import java.security.MessageDigest

import com.github.lavrov.bencode
import com.github.lavrov.bencode.Bencode
import scodec.bits.ByteVector

object util {

  def sha1Hash(value: Bencode): ByteVector = {
    val bytes = bencode.encode(value).toByteArray
    val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
    ByteVector(digest)
  }

}
