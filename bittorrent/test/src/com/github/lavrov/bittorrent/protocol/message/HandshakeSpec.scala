package com.github.lavrov.bittorrent.protocol.message

import com.github.lavrov.bittorrent.{InfoHash, PeerId}
import org.scalatest.FlatSpec
import org.scalatest.MustMatchers._
import org.scalatest.Inside.inside
import scodec.bits.ByteVector

import scala.util.chaining._

class HandshakeSpec extends FlatSpec {

  it should "read and write protocol extension bit" in {
    val message =
      Handshake(
        Set(Extensions.Metadata),
        InfoHash(ByteVector.fill(20)(0)),
        PeerId(0, 0, 0, 0, 0, 0)
      )
    inside(Handshake.HandshakeCodec.encode(message).toOption){
      case Some(bits) =>
        bits.splitAt(20 * 8)
          .pipe {
            case (_, bits) =>
              bits.splitAt(64)
          }
          .pipe {
            case (reserved, bits) =>
              reserved.get(42) mustBe false
              reserved.get(43) mustBe true
              reserved.get(44) mustBe false
          }
    }
  }
}
