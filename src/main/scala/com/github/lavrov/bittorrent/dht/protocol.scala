package com.github.lavrov.bittorrent.dht.protocol

import cats.implicits._
import com.github.lavrov.bencode.Bencode
import com.github.lavrov.bittorrent.dht.NodeId
import com.github.lavrov.bencode.reader._
import scodec.bits.ByteVector

sealed trait Message {
  def transactionId: String
}
object Message {
  final case class QueryMessage(transactionId: String, query: Query) extends Message
  final case class ResponseMessage(transactionId: String, returnValue: Bencode) extends Message
  final case class ErrorMessage(transactionId: String) extends Message

  implicit val NodeIdFormat: BencodeFormat[NodeId] = BencodeFormat.ByteVectorReader.imap(NodeId.apply)(_.bytes)

  val PingQueryFormat: DictionaryFormat[Query.Ping] = (
    matchField[String]("q", "ping"),
    field[NodeId]("a")(field[NodeId]("id").generalize)
  ).imapN((_, qni) => Query.Ping(qni))(v => ((), v.queryingNodeId))

  val FindNodeQueryFormat: DictionaryFormat[Query.FindNode] = (
    matchField[String]("q", "find_node"),
    field[(NodeId, NodeId)]("a")((field[NodeId]("id") and field[NodeId]("target")).generalize)
  ).imapN((_, tpl) => Query.FindNode.tupled(tpl))(v => ((), (v.queryingNodeId, v.target)))

  val QueryFormat: DictionaryFormat[Query] =
    PingQueryFormat.upcast[Query] or FindNodeQueryFormat.upcast[Query]

  val QueryMessageFormat: BencodeFormat[Message.QueryMessage] = (
    matchField[String]("y", "q"),
    field[String]("t"),
    QueryFormat
  ).imapN((_, tid, q) => QueryMessage(tid, q))(v => ((), v.transactionId, v.query))
    .generalize

  val ResponseMessageFormat: BencodeFormat[Message.ResponseMessage] = (
    matchField[String]("y", "r"),
    field[String]("t"),
    field[Bencode]("r")
  ).imapN((_, tid, r) => ResponseMessage(tid, r))(v => ((), v.transactionId, v.returnValue))
    .generalize

  val ErrorMessageFormat: BencodeFormat[Message.ErrorMessage] = (
    matchField[String]("y", "e"),
    field[String]("t")
  ).imapN((_, tid) => ErrorMessage(tid))(v => ((), v.transactionId))
    .generalize

  implicit val MessageFormat: BencodeFormat[Message] =
    QueryMessageFormat.upcast[Message] or ResponseMessageFormat.upcast or ErrorMessageFormat.upcast
}

sealed trait Query {
  def queryingNodeId: NodeId
}
object Query {
  final case class Ping(queryingNodeId: NodeId) extends Query
  final case class FindNode(queryingNodeId: NodeId, target: NodeId) extends Query
}
