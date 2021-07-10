package com.github.lavrov.bittorrent.dht

import cats.implicits.*
import com.github.torrentdam.bencode.Bencode
import com.github.torrentdam.bencode.format.*
import com.github.lavrov.bittorrent.{InfoHash, PeerInfo}
import scodec.Codec
import scodec.bits.ByteVector
import com.comcast.ip4s.*

sealed trait Message {
  def transactionId: ByteVector
}
object Message {
  final case class QueryMessage(transactionId: ByteVector, query: Query) extends Message
  final case class ResponseMessage(transactionId: ByteVector, response: Response) extends Message
  final case class ErrorMessage(transactionId: ByteVector, details: Bencode) extends Message

  given BencodeFormat[NodeId] =
    BencodeFormat.ByteVectorFormat.imap(NodeId.apply)(_.bytes)

  val PingQueryFormat: BencodeFormat[Query.Ping] = (
    field[NodeId]("a")(field[NodeId]("id"))
  ).imap(qni => Query.Ping(qni))(v => v.queryingNodeId)

  val FindNodeQueryFormat: BencodeFormat[Query.FindNode] = (
    field[(NodeId, NodeId)]("a")(
      (field[NodeId]("id"), field[NodeId]("target")).tupled
    )
  ).imap(tpl => Query.FindNode.apply.tupled(tpl))(v => (v.queryingNodeId, v.target))

  given BencodeFormat[InfoHash] = BencodeFormat.ByteVectorFormat.imap(InfoHash(_))(_.bytes)

  val GetPeersQueryFormat: BencodeFormat[Query.GetPeers] = (
    field[(NodeId, InfoHash)]("a")(
      (field[NodeId]("id"), field[InfoHash]("info_hash")).tupled
    )
  ).imap(Query.GetPeers.apply.tupled)(v => (v.queryingNodeId, v.infoHash))

  val AnnouncePeerQueryFormat: BencodeFormat[Query.AnnouncePeer] = (
    field[(NodeId, InfoHash, Long)]("a")(
      (field[NodeId]("id"), field[InfoHash]("info_hash"), field[Long]("port")).tupled
    )
  ).imap(Query.AnnouncePeer.apply.tupled)(v => (v.queryingNodeId, v.infoHash, v.port))

  val SampleInfoHashesQueryFormat: BencodeFormat[Query.SampleInfoHashes] = (
    field[(NodeId, NodeId)]("a")(
      (field[NodeId]("id"), field[NodeId]("target")).tupled
    )
    ).imap(Query.SampleInfoHashes.apply.tupled)(v => (v.queryingNodeId, v.target))

  val QueryFormat: BencodeFormat[Query] =
    field[String]("q").choose(
      {
        case "ping" => PingQueryFormat.upcast
        case "find_node" => FindNodeQueryFormat.upcast
        case "get_peers" => GetPeersQueryFormat.upcast
        case "announce_peer" => AnnouncePeerQueryFormat.upcast
        case "sample_infohashes" => SampleInfoHashesQueryFormat.upcast
      },
      {
        case _: Query.Ping => "ping"
        case _: Query.FindNode => "find_node"
        case _: Query.GetPeers => "get_peers"
        case _: Query.AnnouncePeer => "announce_peer"
        case _: Query.SampleInfoHashes => "sample_infohashes"
      }
    )

  val QueryMessageFormat: BencodeFormat[Message.QueryMessage] = (
    field[ByteVector]("t"),
    QueryFormat
  ).imapN((tid, q) => QueryMessage(tid, q))(v => (v.transactionId, v.query))

  val InetSocketAddressCodec: Codec[SocketAddress[IpAddress]] = {
    import scodec.codecs.*
    (bytes(4) :: bytes(2)).xmap(
      {
        case (address, port) =>
          SocketAddress(
            IpAddress.fromBytes(address.toArray).get,
            Port.fromInt(port.toInt(signed = false)).get
          )
      },
      v => (ByteVector(v.host.toBytes), ByteVector.fromInt(v.port.value, 2))
    )
  }

  val CompactNodeInfoCodec: Codec[List[NodeInfo]] = {
    import scodec.codecs.*
    list(
      (bytes(20) :: InetSocketAddressCodec).xmap(
        {
          case (id, address) =>
            NodeInfo(NodeId(id), address)
        },
        v => (v.id.bytes, v.address)
      )
    )
  }

  val CompactPeerInfoCodec: Codec[PeerInfo] = InetSocketAddressCodec.xmap(PeerInfo.apply, _.address)

  val CompactInfoHashCodec: Codec[List[InfoHash]] = {
    import scodec.codecs.*
    list(
      (bytes(20)).xmap(InfoHash.apply, _.bytes)
    )
  }

  val PingResponseFormat: BencodeFormat[Response.Ping] =
    field[NodeId]("id").imap(Response.Ping.apply)(_.id)

  val NodesResponseFormat: BencodeFormat[Response.Nodes] = (
    field[NodeId]("id"),
    field[List[NodeInfo]]("nodes")(encodedString(CompactNodeInfoCodec))
  ).imapN(Response.Nodes.apply)(v => (v.id, v.nodes))

  val PeersResponseFormat: BencodeFormat[Response.Peers] = (
    field[NodeId]("id"),
    field[List[PeerInfo]]("values")(BencodeFormat.listFormat(encodedString(CompactPeerInfoCodec)))
  ).imapN(Response.Peers.apply)(v => (v.id, v.peers))

  val SampleInfoHashesResponseFormat: BencodeFormat[Response.SampleInfoHashes] = (
    field[NodeId]("id"),
    fieldOptional[List[NodeInfo]]("nodes")(encodedString(CompactNodeInfoCodec)),
    field[List[InfoHash]]("samples")(encodedString(CompactInfoHashCodec))
    ).imapN(Response.SampleInfoHashes.apply)(v => (v.id, v.nodes, v.samples))

  val ResponseFormat: BencodeFormat[Response] =
    BencodeFormat(
      BencodeFormat.dictionaryFormat.read.flatMap {
        case Bencode.BDictionary(dictionary) if dictionary.contains("values") => PeersResponseFormat.read.widen
        case Bencode.BDictionary(dictionary) if dictionary.contains("samples") => SampleInfoHashesResponseFormat.read.widen
        case Bencode.BDictionary(dictionary) if dictionary.contains("nodes") => NodesResponseFormat.read.widen
        case _ => PingResponseFormat.read.widen
      },
      BencodeWriter {
        case value: Response.Peers => PeersResponseFormat.write(value)
        case value: Response.Nodes => NodesResponseFormat.write(value)
        case value: Response.Ping => PingResponseFormat.write(value)
        case value: Response.SampleInfoHashes => SampleInfoHashesResponseFormat.write(value)
      }
    )

  val ResponseMessageFormat: BencodeFormat[Message.ResponseMessage] = (
    field[ByteVector]("t"),
    field[Response]("r")(ResponseFormat)
  ).imapN((tid, r) => ResponseMessage(tid, r))(v => (v.transactionId, v.response))

  val ErrorMessageFormat: BencodeFormat[Message.ErrorMessage] = (
    fieldOptional[ByteVector]("t"),
    field[Bencode]("e")
  ).imapN((tid, details) => ErrorMessage(tid.getOrElse(ByteVector.empty), details))(v =>
    (v.transactionId.some, v.details)
  )

  given BencodeFormat[Message] =
    field[String]("y").choose(
      {
        case "q" => QueryMessageFormat.upcast
        case "r" => ResponseMessageFormat.upcast
        case "e" => ErrorMessageFormat.upcast
      },
      {
        case _: Message.QueryMessage => "q"
        case _: Message.ResponseMessage => "r"
        case _: Message.ErrorMessage => "e"
      }
    )
}

sealed trait Query {
  def queryingNodeId: NodeId
}
object Query {
  final case class Ping(queryingNodeId: NodeId) extends Query
  final case class FindNode(queryingNodeId: NodeId, target: NodeId) extends Query
  final case class GetPeers(queryingNodeId: NodeId, infoHash: InfoHash) extends Query
  final case class AnnouncePeer(queryingNodeId: NodeId, infoHash: InfoHash, port: Long) extends Query
  final case class SampleInfoHashes(queryingNodeId: NodeId, target: NodeId) extends Query
}

sealed trait Response
object Response {
  final case class Ping(id: NodeId) extends Response
  final case class Nodes(id: NodeId, nodes: List[NodeInfo]) extends Response
  final case class Peers(id: NodeId, peers: List[PeerInfo]) extends Response
  final case class SampleInfoHashes(id: NodeId, nodes: Option[List[NodeInfo]], samples: List[InfoHash]) extends Response
}
