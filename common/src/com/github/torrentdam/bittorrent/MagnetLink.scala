package com.github.torrentdam.bittorrent

import com.github.torrentdam.bittorrent.InfoHash

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

  private def urlDecode(value: String): String =
    val bytes = scala.collection.mutable.ArrayBuilder.make[Byte]
    def hex(c: Char): Int = Character.digit(c, 16)
    var i = 0
    while i < value.length do
      value.charAt(i) match
        case '+' =>
          bytes += ' '.toByte
        case '%' if i + 2 < value.length && hex(value.charAt(i + 1)) >= 0 && hex(value.charAt(i + 2)) >= 0 =>
          bytes += (hex(value.charAt(i + 1)) << 4 | hex(value.charAt(i + 2))).toByte
          i += 2
        case c =>
          bytes += c.toByte
      i += 1
    new String(bytes.result(), "UTF-8")
}
