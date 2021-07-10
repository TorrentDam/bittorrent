package com.github.lavrov.bittorrent

import com.comcast.ip4s.*

final case class PeerInfo(address: SocketAddress[IpAddress])
