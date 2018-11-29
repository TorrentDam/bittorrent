package com.github.lavrov.bittorrent.tracker.client

import java.net.InetAddress

import scodec.bits.ByteVector
import spinoco.protocol.http.Uri

case class TrackerRequest(
    announce: Uri,
    infoHash: ByteVector,
    peerId: ByteVector,
    port: Int,
    uploaded: Long,
    downloaded: Long,
    left: Long,
    compact: Boolean = true,
    noPeerId: Boolean = true,
    event: Option[String] = None,
    ip: Option[InetAddress] = None,
    numWant: Int = 50,
    key: Option[String] = None,
    trackerId: Option[String] = None)
