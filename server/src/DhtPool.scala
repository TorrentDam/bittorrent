import cats.effect.{ContextShift, IO, Timer}
import cats.effect.concurrent.{MVar, Ref}
import cats.implicits._
import com.github.lavrov.bittorrent.InfoHash
import com.github.lavrov.bittorrent.dht.Query
import com.github.lavrov.bittorrent.dht.{Node, NodeId, NodeInfo, QueryHandler, RoutingTable, RoutingTableBootstrap}
import fs2.io.udp.SocketGroup
import fs2.Stream
import fs2.concurrent.Queue
import logstage.LogIO

import scala.concurrent.duration.DurationInt
import scala.util.Random

object DhtPool {

  def fishForInfoHashes(seedNode: NodeInfo)(implicit
    contextShift: ContextShift[IO],
    timer: Timer[IO],
    socketGroup: SocketGroup,
    logger: LogIO[IO]
  ): Stream[IO, InfoHash] = {

    val randomNodeId = {
      val rnd = new Random
      IO { NodeId.generate(rnd) }
    }

    def spawn(reportInfoHash: InfoHash => IO[Unit]): IO[Unit] =
      for {
        mvar <- MVar.empty[IO, InfoHash]
        nodeId <- randomNodeId
        routingTable <- RoutingTable[IO](nodeId)
        queryHandler <- QueryHandler[IO](nodeId, routingTable).pure[IO]
        queryHandler <-
          QueryHandler
            .fromFunction[IO] { (address, query) =>
              val reportGetPeers = query match {
                case Query.GetPeers(_, infoHash) =>
                  mvar.put(infoHash)
                case _ => IO.unit
              }
              reportGetPeers >> queryHandler(address, query)
            }
            .pure[IO]
        _ <- Node[IO](nodeId, 0, queryHandler).use { node =>
          RoutingTableBootstrap
            .search(
              nodeId,
              List(seedNode),
              routingTable.insert,
              nodeInfo =>
                node.client
                  .findNodes(nodeInfo)
                  .map(_.nodes)
            )
            .background
            .use { _ =>
              mvar.take
                .timeout(1.hour)
                .flatMap(reportInfoHash)
                .foreverM
                .attempt
            }
        }
      } yield ()

    Stream
      .eval(Queue.unbounded[IO, InfoHash])
      .flatMap { queue =>
        queue.dequeue.concurrently {
          Stream
            .fixedRate(30.seconds)
            .parEvalMapUnordered(1000) { _ =>
              spawn(queue.enqueue1).attempt
            }
        }
      }
  }

}
