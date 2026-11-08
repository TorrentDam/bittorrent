package com.github.torrentdam.bittorrent.dht

import cats.effect.IO
import cats.syntax.all.*
import cats.effect.std.Random
import org.legogroup.woof.{Logger, given}

import scala.concurrent.duration.{DurationInt, FiniteDuration}

class RoutingTableRefresh(table: RoutingTable, client: Client, discovery: PeerDiscovery)(using logger: Logger[IO], random: Random[IO]):
  
  def runOnce: IO[Unit] =
    table.buckets.flatMap { buckets =>
      val (fresh, stale) = buckets.toList.partition(_.nodes.values.exists(_.isGood))
      val refresh = if stale.nonEmpty then refreshBuckets(stale) else IO.unit
      refresh >> pingNodes(fresh.flatMap(_.nodes.values))
    }

  def runEvery(period: FiniteDuration): IO[Unit] =
    IO
      .sleep(period)
      .productR(runOnce)
      .foreverM
      .handleErrorWith: e => 
        logger.error(s"PingRoutine failed: $e")
      .foreverM
  
  private def pingNodes(nodes: List[RoutingTable.Node]): IO[Unit] =
    logger.info(s"Pinging ${nodes.size} nodes") >>
      nodes
        .parTraverse { node =>
          client.ping(node.address).timeout(5.seconds).attempt.map(_.bimap(_ => node.id, _ => node.id))
        }
        .flatMap { results =>
          val (bad, good) = results.partitionMap(identity)
          logger.info(s"Got ${good.size} good nodes and ${bad.size} bad nodes") >>
            table.updateGoodness(good.toSet, bad.toSet)
        }

  private def refreshBuckets(buckets: List[RoutingTable.TreeNode.Bucket]): IO[Unit] =
    logger.info(s"Found ${buckets.size} stale buckets") >>
      buckets
        .parTraverse: bucket =>
          NodeId.randomInRange(bucket.from, bucket.until).flatMap: randomId =>
            discovery.findNodes(randomId).take(32).compile.drain
        .void

    
