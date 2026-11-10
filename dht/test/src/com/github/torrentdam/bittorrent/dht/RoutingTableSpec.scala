package com.github.torrentdam.bittorrent.dht

import cats.effect.IO
import cats.effect.kernel.Clock
import cats.effect.kernel.Ref

import com.comcast.ip4s.*
import com.comcast.ip4s.Port
import com.comcast.ip4s.SocketAddress
import com.github.torrentdam.bittorrent.InfoHash
import com.github.torrentdam.bittorrent.PeerInfo
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scodec.bits.ByteVector

class RoutingTableSpec extends munit.CatsEffectSuite {

  private def infoHash(i: Int): InfoHash = InfoHash(ByteVector.view(BigInt(i).toByteArray).padLeft(20))

  private def peer(port: Int): PeerInfo =
    PeerInfo(SocketAddress(ip"10.0.0.1", Port.fromInt(port).get))

  private def withRoutingTable[A](test: (RoutingTable, FiniteDuration => IO[Unit]) => IO[A]): IO[A] =
    Ref.of[IO, FiniteDuration](Duration.Zero).flatMap { ref =>
      given Clock[IO] = new Clock[IO] {
        def applicative: cats.Applicative[IO] = summon
        def realTime: IO[FiniteDuration] = ref.get
        def monotonic: IO[FiniteDuration] = ref.get
        def sleep(d: FiniteDuration): IO[Unit] = IO.unit
      }
      RoutingTable(NodeId.fromInt(0)).flatMap(table => test(table, d => ref.update(_ + d)))
    }

  test("addPeer and findPeers roundtrip") {
    withRoutingTable { (table, _) =>
      for {
        _ <- table.addPeer(infoHash(1), peer(1))
        _ <- table.addPeer(infoHash(1), peer(2))
        _ <- table.addPeer(infoHash(1), peer(1)) // re-announce must not duplicate
        peers <- table.findPeers(infoHash(1))
        missing <- table.findPeers(infoHash(2))
      } yield {
        assertEquals(peers.map(_.toSet), Some(Set(peer(1), peer(2))))
        assertEquals(missing, None)
      }
    }
  }

  test("peers per info hash are capped, keeping the most recent") {
    withRoutingTable { (table, _) =>
      for {
        _ <- (1 to RoutingTable.MaxPeersPerInfoHash + 10).toList.foldLeft(IO.unit)((acc, i) => acc *> table.addPeer(infoHash(1), peer(i)))
        peers <- table.findPeers(infoHash(1))
      } yield {
        val expected = (11 to RoutingTable.MaxPeersPerInfoHash + 10).map(peer).toSet
        assertEquals(peers.map(_.toSet), Some(expected))
        assertEquals(peers.map(_.size), Some(RoutingTable.MaxPeersPerInfoHash))
      }
    }
  }

  test("tracked info hashes are capped, evicting the least recently announced") {
    withRoutingTable { (table, _) =>
      for {
        _ <- (0 until RoutingTable.MaxTrackedInfoHashes + 10).toList.foldLeft(IO.unit)((acc, i) => acc *> table.addPeer(infoHash(i), peer(1)))
        eldest <- table.findPeers(infoHash(0))
        newest <- table.findPeers(infoHash(RoutingTable.MaxTrackedInfoHashes + 9))
        // re-announcing an evicted info hash makes it most recent, so the next eldest is evicted
        _ <- table.addPeer(infoHash(0), peer(2))
        revived <- table.findPeers(infoHash(0))
        evicted <- table.findPeers(infoHash(10))
      } yield {
        assertEquals(eldest, None)
        assertEquals(newest.map(_.toSet), Some(Set(peer(1))))
        assertEquals(revived.map(_.toSet), Some(Set(peer(2))))
        assertEquals(evicted, None)
      }
    }
  }

  test("peer entries expire after the TTL, pruned on the next announce") {
    withRoutingTable { (table, advance) =>
      for {
        _ <- table.addPeer(infoHash(1), peer(1))
        _ <- advance(RoutingTable.PeerEntryTtl - 1.minute)
        fresh <- table.findPeers(infoHash(1))
        _ <- advance(2.minutes)
        // reads do not filter by time; the next announce prunes the expired entry
        _ <- table.addPeer(infoHash(2), peer(9))
        expired <- table.findPeers(infoHash(1))
        // a new announce for the expired info hash revives it without the stale peer
        _ <- table.addPeer(infoHash(1), peer(2))
        revived <- table.findPeers(infoHash(1))
      } yield {
        assertEquals(fresh.map(_.toSet), Some(Set(peer(1))))
        assertEquals(expired, None)
        assertEquals(revived.map(_.toSet), Some(Set(peer(2))))
      }
    }
  }
}
