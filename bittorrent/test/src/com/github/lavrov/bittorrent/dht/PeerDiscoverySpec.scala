package com.github.lavrov.bittorrent.dht

import verify._

import cats.effect.SyncIO
import cats.effect.concurrent.Ref
import com.github.lavrov.bittorrent.PeerInfo
import com.github.lavrov.bittorrent.InfoHash
import scodec.bits.ByteVector
import com.github.lavrov.bittorrent.dht.message.Response
import java.net.InetSocketAddress


class PeerDiscoverySpec extends BasicTestSuite {

  test("discover new peers") {
    type F[A] = SyncIO[A]

    def nodeId(id: String) = NodeId(ByteVector.encodeUtf8(id).right.get)

    val nodesToTry = Ref
      .of[F, List[NodeInfo]](
        List(
          NodeInfo(
            nodeId("a"),
            InetSocketAddress.createUnresolved("1.1.1.1", 1)
          )
        )
      )
      .unsafeRunSync()
    val seenNodes = Ref.of[F, Set[NodeInfo]](Set.empty).unsafeRunSync()
    val seenPeers = Ref.of[F, Set[PeerInfo]](Set.empty).unsafeRunSync()

    val infoHash = InfoHash(ByteVector.encodeUtf8("c").right.get)

    def getPeers(
        nodeInfo: NodeInfo,
        infoHash: InfoHash
    ): F[Either[Response.Nodes, Response.Peers]] = SyncIO {
      nodeInfo.address.getPort() match {
        case 1 =>
          Left(
            Response.Nodes(
              nodeId("a"),
              List(
                NodeInfo(
                  nodeId("b"),
                  InetSocketAddress.createUnresolved("1.1.1.1", 2)
                ),
                NodeInfo(
                  nodeId("c"),
                  InetSocketAddress.createUnresolved("1.1.1.1", 3)
                )
              )
            )
          )
        case 2 =>
          Right(
            Response.Peers(
              nodeId("b"),
              List(
                PeerInfo(
                  InetSocketAddress.createUnresolved("2.2.2.2", 2)
                )
              )
            )
          )
        case 3 =>
          Right(
            Response.Peers(
              nodeId("c"),
              List(
                PeerInfo(
                  InetSocketAddress.createUnresolved("2.2.2.2", 3)
                )
              )
            )
          )
      }
    }

//    val stream = PeerDiscovery.start(infoHash, nodesToTry, seenNodes, seenPeers, getPeers)
//
//    stream.take(1).compile.toList.unsafeRunSync shouldBe List(
//      PeerInfo(InetSocketAddress.createUnresolved("2.2.2.2", 3))
//    )
    ()
  }
}
