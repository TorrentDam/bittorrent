package com.github.torrentdam.bittorrent.protocol.extensions

import com.github.torrentdam.bittorrent.protocol.message.Message

object Extensions {

  object MessageId {
    val Handshake = 0L
    val Metadata = 1L
    val Pex = 2L
  }

  def handshake: ExtensionHandshake =
    ExtensionHandshake(
      Map(
        ("ut_metadata", MessageId.Metadata),
        ("ut_pex", MessageId.Pex)
      ),
      None
    )
}
