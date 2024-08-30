package com.github.torrentdam.bittorrent.dht

import cats.implicits.*
import com.comcast.ip4s.*
import com.github.torrentdam.bittorrent.InfoHash
import com.github.torrentdam.bittorrent.PeerInfo
import com.github.torrentdam.bencode.format.*
import com.github.torrentdam.bencode.Bencode
import scodec.bits.ByteVector
import scodec.Codec

enum Message:
  case QueryMessage(transactionId: ByteVector, query: Query)
  case ResponseMessage(transactionId: ByteVector, response: Response)
  case ErrorMessage(transactionId: ByteVector, details: Bencode)

  def transactionId: ByteVector
end Message

object Message {

  given BencodeFormat[NodeId] =
    BencodeFormat.ByteVectorFormat.imap(NodeId.apply)(_.bytes)

  val PingQueryFormat: BencodeFormat[Query.Ping] = (
    field[NodeId]("a")(field[NodeId]("id"))
  ).imap[Query.Ping](qni => Query.Ping(qni))(v => v.queryingNodeId)

  val FindNodeQueryFormat: BencodeFormat[Query.FindNode] = (
    field[(NodeId, NodeId)]("a")(
      (field[NodeId]("id"), field[NodeId]("target")).tupled
    )
  ).imap[Query.FindNode](Query.FindNode.apply)(v => (v.queryingNodeId, v.target))

  given BencodeFormat[InfoHash] = BencodeFormat.ByteVectorFormat.imap(InfoHash(_))(_.bytes)

  val GetPeersQueryFormat: BencodeFormat[Query.GetPeers] = (
    field[(NodeId, InfoHash)]("a")(
      (field[NodeId]("id"), field[InfoHash]("info_hash")).tupled
    )
  ).imap[Query.GetPeers](Query.GetPeers.apply)(v => (v.queryingNodeId, v.infoHash))

  val AnnouncePeerQueryFormat: BencodeFormat[Query.AnnouncePeer] = (
    field[(NodeId, InfoHash, Long)]("a")(
      (field[NodeId]("id"), field[InfoHash]("info_hash"), field[Long]("port")).tupled
    )
  ).imap[Query.AnnouncePeer](Query.AnnouncePeer.apply)(v => (v.queryingNodeId, v.infoHash, v.port))

  val SampleInfoHashesQueryFormat: BencodeFormat[Query.SampleInfoHashes] = (
    field[(NodeId, NodeId)]("a")(
      (field[NodeId]("id"), field[NodeId]("target")).tupled
    )
  ).imap[Query.SampleInfoHashes](Query.SampleInfoHashes.apply)(v => (v.queryingNodeId, v.target))

  val QueryFormat: BencodeFormat[Query] =
    field[String]("q").choose(
      {
        case "ping"              => PingQueryFormat.upcast
        case "find_node"         => FindNodeQueryFormat.upcast
        case "get_peers"         => GetPeersQueryFormat.upcast
        case "announce_peer"     => AnnouncePeerQueryFormat.upcast
        case "sample_infohashes" => SampleInfoHashesQueryFormat.upcast
      },
      {
        case _: Query.Ping             => "ping"
        case _: Query.FindNode         => "find_node"
        case _: Query.GetPeers         => "get_peers"
        case _: Query.AnnouncePeer     => "announce_peer"
        case _: Query.SampleInfoHashes => "sample_infohashes"
      }
    )

  val QueryMessageFormat: BencodeFormat[Message.QueryMessage] = (
    field[ByteVector]("t"),
    QueryFormat
  ).imapN[QueryMessage]((tid, q) => QueryMessage(tid, q))(v => (v.transactionId, v.query))

  val InetSocketAddressCodec: Codec[SocketAddress[IpAddress]] = {
    import scodec.codecs.*
    (bytes(4) :: bytes(2)).xmap(
      { case (address, port) =>
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
        { case (id, address) =>
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
    field[NodeId]("id").imap[Response.Ping](Response.Ping.apply)(_.id)

  val NodesResponseFormat: BencodeFormat[Response.Nodes] = (
    field[NodeId]("id"),
    field[List[NodeInfo]]("nodes")(encodedString(CompactNodeInfoCodec))
  ).imapN[Response.Nodes](Response.Nodes.apply)(v => (v.id, v.nodes))

  val PeersResponseFormat: BencodeFormat[Response.Peers] = (
    field[NodeId]("id"),
    field[List[PeerInfo]]("values")(using BencodeFormat.listFormat(using encodedString(CompactPeerInfoCodec)))
  ).imapN[Response.Peers](Response.Peers.apply)(v => (v.id, v.peers))

  val SampleInfoHashesResponseFormat: BencodeFormat[Response.SampleInfoHashes] = (
    field[NodeId]("id"),
    fieldOptional[List[NodeInfo]]("nodes")(encodedString(CompactNodeInfoCodec)),
    field[List[InfoHash]]("samples")(encodedString(CompactInfoHashCodec))
  ).imapN[Response.SampleInfoHashes](Response.SampleInfoHashes.apply)(v => (v.id, v.nodes, v.samples))

  val ResponseFormat: BencodeFormat[Response] =
    BencodeFormat(
      BencodeFormat.dictionaryFormat.read.flatMap {
        case Bencode.BDictionary(dictionary) if dictionary.contains("values") => PeersResponseFormat.read.widen
        case Bencode.BDictionary(dictionary) if dictionary.contains("samples") =>
          SampleInfoHashesResponseFormat.read.widen
        case Bencode.BDictionary(dictionary) if dictionary.contains("nodes") => NodesResponseFormat.read.widen
        case _                                                               => PingResponseFormat.read.widen
      },
      BencodeWriter {
        case value: Response.Peers            => PeersResponseFormat.write(value)
        case value: Response.Nodes            => NodesResponseFormat.write(value)
        case value: Response.Ping             => PingResponseFormat.write(value)
        case value: Response.SampleInfoHashes => SampleInfoHashesResponseFormat.write(value)
      }
    )

  val ResponseMessageFormat: BencodeFormat[Message.ResponseMessage] = (
    field[ByteVector]("t"),
    field[Response]("r")(ResponseFormat)
  ).imapN[ResponseMessage]((tid, r) => ResponseMessage(tid, r))(v => (v.transactionId, v.response))

  val ErrorMessageFormat: BencodeFormat[Message.ErrorMessage] = (
    fieldOptional[ByteVector]("t"),
    field[Bencode]("e")
  ).imapN[ErrorMessage]((tid, details) => ErrorMessage(tid.getOrElse(ByteVector.empty), details))(v =>
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
        case _: Message.QueryMessage    => "q"
        case _: Message.ResponseMessage => "r"
        case _: Message.ErrorMessage    => "e"
      }
    )
}

enum Query:
  case Ping(queryingNodeId: NodeId)
  case FindNode(queryingNodeId: NodeId, target: NodeId)
  case GetPeers(queryingNodeId: NodeId, infoHash: InfoHash)
  case AnnouncePeer(queryingNodeId: NodeId, infoHash: InfoHash, port: Long)
  case SampleInfoHashes(queryingNodeId: NodeId, target: NodeId)

  def queryingNodeId: NodeId
end Query

enum Response:
  case Ping(id: NodeId)
  case Nodes(id: NodeId, nodes: List[NodeInfo])
  case Peers(id: NodeId, peers: List[PeerInfo])
  case SampleInfoHashes(id: NodeId, nodes: Option[List[NodeInfo]], samples: List[InfoHash])
