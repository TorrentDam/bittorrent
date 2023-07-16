package com.github.torrentdam.bittorrent

import com.github.torrentdam.bittorrent.InfoHash
import java.net.URLDecoder
import java.nio.charset.Charset

case class MagnetLink(infoHash: InfoHash, displayName: Option[String], trackers: List[String])

object MagnetLink {

  def fromString(source: String): Option[MagnetLink] =
    source match
      case s"magnet:?$query" => fromQueryString(query)
      case _                 => None

  private def fromQueryString(str: String) =
    val params = parseQueryString(str)
    for
      infoHash <- getInfoHash(params)
      displayName = getDisplayName(params)
      trackers = getTrackers(params)
    yield MagnetLink(infoHash, displayName, trackers)

  private type Query = Map[String, List[String]]

  private def getInfoHash(query: Query): Option[InfoHash] =
    query.get("xt").flatMap {
      case List(s"urn:btih:${InfoHash.fromString(ih)}") => Some(ih)
      case _                                            => None
    }

  private def getDisplayName(query: Query): Option[String] =
    query.get("dn").flatMap(_.headOption)

  private def getTrackers(query: Query): List[String] =
    query.get("tr").toList.flatten

  private def parseQueryString(str: String): Query =
    str
      .split('&')
      .toList
      .map(urlDecode)
      .map { p =>
        val split = p.split('=')
        (split.head, split.tail.mkString("="))
      }
      .groupMap(_._1)(_._2)

  private def urlDecode(value: String) =
    URLDecoder.decode(value, Charset.forName("UTF-8"))
}
