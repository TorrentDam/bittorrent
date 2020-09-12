package com.github.lavrov.bittorrent.protocol.message

import com.github.lavrov.bittorrent.{InfoHash, PeerId}
import scodec.bits.ByteVector

import scala.util.chaining._

class HandshakeSpec extends munit.FunSuite {

  test("read and write protocol extension bit") {
    val message =
      Handshake(
        true,
        InfoHash(ByteVector.fill(20)(0)),
        PeerId(0, 0, 0, 0, 0, 0)
      )
    assert(
      PartialFunction.cond(Handshake.HandshakeCodec.encode(message).toOption) {
        case Some(bits) =>
          bits.splitAt(20 * 8)
            .pipe {
              case (_, bits) =>
                bits.splitAt(64)
            }
            .pipe {
              case (reserved, bits) =>
                assert(reserved.get(42) == false)
                assert(reserved.get(43) == true)
                assert(reserved.get(44) == false)
            }
            true
            case _ => false
      }
    )
  }
}
