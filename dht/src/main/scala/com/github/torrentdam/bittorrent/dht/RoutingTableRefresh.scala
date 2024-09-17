package com.github.torrentdam.bittorrent.dht

import cats.effect.IO
import cats.syntax.all.*
import cats.effect.cps.{*, given}
import cats.effect.std.Random
import org.legogroup.woof.{Logger, given}

import scala.concurrent.duration.{DurationInt, FiniteDuration}

class RoutingTableRefresh(table: RoutingTable[IO], client: Client, discovery: PeerDiscovery)(using logger: Logger[IO], random: Random[IO]):
  
  def runOnce: IO[Unit] = async[IO]:
    val buckets = table.buckets.await
    val (fresh, stale) = buckets.toList.partition(_.nodes.values.exists(_.isGood))
    if stale.nonEmpty then
      refreshBuckets(stale).await
    val nodes = fresh.flatMap(_.nodes.values)
    pingNodes(nodes).await
  
  def runEvery(period: FiniteDuration): IO[Unit] =
    IO
      .sleep(period)
      .productR(runOnce)
      .foreverM
      .handleErrorWith: e => 
        logger.error(s"PingRoutine failed: $e")
      .foreverM
  
  private def pingNodes(nodes: List[RoutingTable.Node]) = async[IO]:
    logger.info(s"Pinging ${nodes.size} nodes").await
    val results = nodes
      .parTraverse { node =>
        client.ping(node.address).timeout(5.seconds).attempt.map(_.bimap(_ => node.id, _ => node.id))
      }
      .await
    val (bad, good) = results.partitionMap(identity)
    logger.info(s"Got ${good.size} good nodes and ${bad.size} bad nodes").await
    table.updateGoodness(good.toSet, bad.toSet).await
    
  private def refreshBuckets(buckets: List[RoutingTable.TreeNode.Bucket]) = async[IO]:
    logger.info(s"Found ${buckets.size} stale buckets").await
    buckets
      .parTraverse: bucket =>
        val randomId = NodeId.randomInRange(bucket.from, bucket.until).await
        discovery.findNodes(randomId).take(32).compile.drain
      .await
      
    
