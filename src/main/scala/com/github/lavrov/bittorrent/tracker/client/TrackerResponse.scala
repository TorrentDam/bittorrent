package com.github.lavrov.bittorrent.tracker.client

import java.net.InetSocketAddress

case class TrackerResponse(
    warning: Option[String],
    interval: Int,
    minInterval: Option[Int],
    trackerId: Option[String],
    complete: Option[Int],
    incomplete: Option[Int],
    peers: List[TrackerPeer])

case class TrackerPeer(peerId: Option[String], address: InetSocketAddress)

case class TrackerError(reason: String)
