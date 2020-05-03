package com.github.lavrov.bittorrent.dht

import java.net.InetSocketAddress

import cats.effect.{Concurrent, Resource, Timer}
import cats.effect.concurrent.Ref
import cats.implicits._
import cats.mtl._
import cats.{Monad, MonadError}
import com.github.lavrov.bittorrent.dht.message.{Message, Query, Response}
import com.olegpy.meow.effects.RefEffects
import monocle.Lens
import monocle.macros.GenLens

import scala.concurrent.duration._
import logstage.LogIO

object RoutingTableManager {

  case class State(
    table: List[NodeInfo] = List.empty
  )

  object State {
    val table: Lens[State, List[NodeInfo]] = GenLens[State](_.table)
  }

  def make[F[_]: Concurrent](
    selfId: NodeId,
    bootstrapNodeInfo: NodeInfo,
    receive: F[(InetSocketAddress, Message.QueryMessage)],
    sendOut: (InetSocketAddress, Message) => F[Unit]
  ): Resource[F, Ref[F, State]] =
    Resource {
      for {
        ref <- Ref.of(State(table = List(bootstrapNodeInfo)))
        b = ref.runState { implicit S =>
          new Behaviour(selfId, sendOut)
        }
        fiber <- Concurrent[F].start(receive >>= (b.onQuery _).tupled)
      } yield {
        (ref, fiber.cancel)
      }
    }

  private class Behaviour[F[_]](
    selfId: NodeId,
    sendOut: (InetSocketAddress, Message) => F[Unit]
  )(implicit
    F: Monad[F],
    S: MonadState[F, State]
  ) {

    def onQuery(address: InetSocketAddress, message: Message.QueryMessage): F[Unit] = {
      message match {
        case Message.QueryMessage(tid, Query.Ping(nodeId)) =>
          sendOut(address, Message.ResponseMessage(tid, Response.Ping(selfId)))
        case Message.QueryMessage(tid, Query.GetPeers(nodeId, infoHash)) =>
          MonadState[F, State]
            .inspect(State.table.get)
            .flatMap { nodes =>
              val response = Response.Nodes(selfId, nodes)
              sendOut(address, Message.ResponseMessage(tid, response))
            }
        case Message.QueryMessage(tid, Query.FindNode(nodeId, target)) =>
          MonadState[F, State]
            .inspect(State.table.get)
            .flatMap { nodes =>
              val response = Response.Nodes(selfId, nodes)
              sendOut(address, Message.ResponseMessage(tid, response))
            }
        case _ =>
          F.unit
      }
    }
  }

  val BootstrapNodeAddress = new InetSocketAddress("router.bittorrent.com", 6881)

  def bootstrap[F[_]](
    client: Client[F],
    logger: LogIO[F]
  )(implicit
    F: MonadError[F, Throwable],
    timer: Timer[F]
  ): F[NodeInfo] = {
    def loop: F[NodeInfo] =
      client
        .ping(BootstrapNodeAddress)
        .map(pong => NodeInfo(pong.id, BootstrapNodeAddress))
        .recoverWith {
          case e =>
            val msg = e.getMessage()
            logger.info(s"Bootstrap failed $msg $e") >> timer.sleep(5.seconds) >> loop
        }
    logger.info("Boostrapping") *> loop <* logger.info("Bootstrap complete")
  }
}
