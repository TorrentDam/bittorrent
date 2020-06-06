package com.github.lavrov.bittorrent.protocol.extensions

import com.github.lavrov.bittorrent.protocol.message.Message

object Extensions {

  object MessageId {
    val Handshake = 0L
    val Metadata = 1L
  }

  def handshakePayload: ExtensionHandshake =
    ExtensionHandshake(
      Map(
        ("ut_metadata", MessageId.Metadata)
      ),
      None
    )

  def handshake: Message.Extended =
    Message.Extended(
      MessageId.Handshake,
      ExtensionHandshake.encode(handshakePayload)
    )
}
