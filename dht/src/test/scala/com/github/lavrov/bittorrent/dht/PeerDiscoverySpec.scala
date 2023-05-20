package com.github.lavrov.bittorrent.dht

import cats.effect.kernel.Ref
import cats.effect.IO
import cats.effect.SyncIO
import com.comcast.ip4s.*
import com.github.lavrov.bittorrent.InfoHash
import com.github.lavrov.bittorrent.PeerInfo
import org.legogroup.woof.Logger
import scodec.bits.ByteVector

class PeerDiscoverySpec extends munit.CatsEffectSuite {

  test("discover new peers") {

    val infoHash = InfoHash(ByteVector.encodeUtf8("c").toOption.get)

    def nodeId(id: String) = NodeId(ByteVector.encodeUtf8(id).toOption.get)

    given logger: Logger[IO] = NoOpLogger()

    def getPeers(
      nodeInfo: NodeInfo,
      infoHash: InfoHash
    ): IO[Either[Response.Nodes, Response.Peers]] = IO {
      nodeInfo.address.port.value match {
        case 1 =>
          Left(
            Response.Nodes(
              nodeId("a"),
              List(
                NodeInfo(
                  nodeId("b"),
                  SocketAddress(ip"1.1.1.1", port"2")
                ),
                NodeInfo(
                  nodeId("c"),
                  SocketAddress(ip"1.1.1.1", port"3")
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
                  SocketAddress(ip"2.2.2.2", port"2")
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
                  SocketAddress(ip"2.2.2.2", port"3")
                )
              )
            )
          )
      }
    }

    for {
      state <- PeerDiscovery.DiscoveryState(
        initialNodes = List(
          NodeInfo(
            nodeId("a"),
            SocketAddress(ip"1.1.1.1", port"1")
          )
        ),
        infoHash = infoHash
      )
      list <- PeerDiscovery.start(infoHash, getPeers, state, 1).take(1).compile.toList
    } yield {
      assertEquals(
        list,
        List(
          PeerInfo(SocketAddress(ip"2.2.2.2", port"3"))
        )
      )
    }
  }
}
