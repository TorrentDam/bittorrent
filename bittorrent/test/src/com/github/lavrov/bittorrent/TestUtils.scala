package com.github.lavrov.bittorrent
import java.io.InputStream

import scodec.bits.ByteVector
import scodec.bits.BitVector

object TestUtils {

  implicit class InputStreamExtensions(is: InputStream) {
    def readAllBytes(): Array[Byte] = {
      val buffer = Array.ofDim[Byte](1024)
      def recur(acc: ByteVector): ByteVector = {
        val bytesRead = is.read(buffer)
        if bytesRead != -1 then
          recur(acc ++ ByteVector(buffer, 0, bytesRead))
        else
          acc
      }
      recur(ByteVector.empty).toArray
    }
    def readAll(): BitVector = BitVector(readAllBytes())
  }

}
