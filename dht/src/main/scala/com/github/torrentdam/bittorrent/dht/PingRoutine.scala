package com.github.torrentdam.bittorrent.dht

import cats.effect.IO
import cats.syntax.all.*
import cats.effect.cps.{*, given}
import org.legogroup.woof.{Logger, given}

import scala.concurrent.duration.DurationInt

class PingRoutine(table: RoutingTable[IO], client: Client)(using logger: Logger[IO]):
  
  def run: IO[Unit] = async[IO]:
    val (nodes, desperateNodes) = table.allNodes.await.partition(_.badCount < 3)
    if desperateNodes.nonEmpty then
      logger.info(s"Removing ${desperateNodes.size} desperate nodes").await
      desperateNodes.traverse_(node => table.remove(node.id)).await
    logger.info(s"Pinging ${nodes.size} nodes").await
    val queries = nodes.map { node =>
        client.ping(node.address).timeout(5.seconds).attempt.map(_.bimap(_ => node.id, _ => node.id))
      }
    val results = queries.parSequence.await
    val (bad, good) = results.partitionMap(identity)
    logger.info(s"Got ${good.size} good nodes and ${bad.size} bad nodes").await
    table.updateGoodness(good.toSet, bad.toSet)
  
  def runForever: IO[Unit] =
    run
      .handleErrorWith: e => 
        logger.error(s"PingRoutine failed: $e")
      .productR(IO.sleep(10.minutes))
      .foreverM
