import cats.effect.{ContextShift, IO, Timer}
import cats.effect.concurrent.{MVar, Ref}
import cats.implicits._
import com.github.lavrov.bittorrent.InfoHash
import com.github.lavrov.bittorrent.dht.{Node, NodeId, NodeInfo, QueryHandler, RoutingTable}
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
        nodeId <- randomNodeId
        routingTable <- RoutingTable[IO](nodeId)
        queryHandler <- QueryHandler[IO](nodeId, routingTable).pure[IO]
        _ <- Node[IO](nodeId, 0, queryHandler).use { node =>
          def loop(nodes: List[NodeInfo], visited: Set[NodeInfo]): IO[Unit] = {
            nodes
              .traverse { nodeInfo =>
                node.client.sampleInfoHashes(nodeInfo, nodeId)
                  .flatMap {
                    case Right(response) =>
                      response.samples.traverse_(reportInfoHash).as(response.nodes.getOrElse(List.empty))
                    case Left(response) =>
                      response.nodes.pure[IO]
                  }
                  .handleErrorWith(_ => List.empty.pure[IO])
              }
              .map(_.flatten.filterNot(visited).take(10))
              .flatMap {
                case Nil => IO.unit
                case nonEmpty => loop(nonEmpty, visited ++ nodes)
              }
          }
          node.client.findNodes(seedNode, nodeId).flatMap { response =>
            loop(response.nodes, Set.empty)
          }
        }
      } yield ()

    Stream
      .eval(Queue.unbounded[IO, InfoHash])
      .flatMap { queue =>
        queue.dequeue.concurrently {
          Stream
            .fixedRate(30.seconds)
            .parEvalMapUnordered(1) { _ =>
              spawn(queue.enqueue1).attempt.timeoutTo(10.minutes, IO.unit)
            }
        }
      }
  }

}
