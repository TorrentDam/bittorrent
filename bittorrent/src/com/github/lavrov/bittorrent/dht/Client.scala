package com.github.lavrov.bittorrent
package dht

import java.net.InetSocketAddress

import cats._
import cats.data.NonEmptyList
import cats.effect.Sync
import cats.implicits._
import fs2.io.udp.{Packet, Socket}
import com.github.lavrov.bencode.{decode, encode}
import com.github.lavrov.bittorrent.dht.message.{Message, Query, Response}
import fs2.Chunk
import fs2.Stream
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger

import scala.concurrent.duration.DurationInt

class Client[F[_]: Monad](selfId: NodeId, socket: Socket[F], logger: Logger[F])(implicit M: MonadError[F, Throwable]) {

  def readMessage: F[Message] =
    for {
      packet <- socket.read(5.seconds.some)
      bc <- M.fromEither(decode(packet.bytes.toArray).left.map(e => new Exception(e.message)))
      message <- M.fromEither(
        Message.MessageFormat
          .read(bc)
          .left
          .map(e => new Exception(s"Filed to read message: $e. Bencode: $bc"))
      )
    } yield message

  def sendMessage(address: InetSocketAddress, message: Message): F[Unit] =
    for {
      bc <- M.fromEither(Message.MessageFormat.write(message).left.map(new Exception(_)))
      bytes = encode(bc)
      _ <- socket.write(Packet(address, Chunk.byteVector(bytes.bytes)))

    } yield ()

  def getPeersAlgo(infoHash: InfoHash): F[Stream[F, PeerInfo]] = {
    def iteration(nodesToTry: NonEmptyList[NodeInfo]): Stream[F, PeerInfo] =
      Stream[F, NodeInfo](nodesToTry.toList: _*)
        .evalMap { nodeInfo =>
          for {
            _ <- sendMessage(
              nodeInfo.address,
              Message.QueryMessage("aa", Query.GetPeers(selfId, infoHash))
            )
            m <- readMessage
            response <- M.fromEither(
              m match {
                case Message.ResponseMessage("aa", bc) =>
                  val reader =
                    Message.PeersResponseFormat.read
                      .widen[Response]
                      .orElse(Message.NodesResponseFormat.read.widen[Response])
                  reader(bc).leftMap(new Exception(_))
                case other =>
                  Left(new Exception(s"Expected response but got $other"))
              }
            )
          } yield response
        }
        .flatMap { response =>
          response match {
            case Response.Nodes(_, nodes) =>
              nodes.sortBy(n => NodeId.distance(n.id, infoHash)).toNel match {
                case Some(ns) => iteration(ns)
                case _ => Stream eval M.raiseError(new Exception("Failed to find peers"))
              }
            case Response.Peers(_, peers) =>
              Stream(peers: _*)
          }
        }
      .recoverWith {
        case e =>
          Stream.eval(logger.error(e)("Failed query")) *> Stream.empty
      }
    for {
      _ <- sendMessage(Client.BootstrapNode, Message.QueryMessage("aa", Query.Ping(selfId)))
      m <- readMessage
      r <- M.fromEither(
        m match {
          case Message.ResponseMessage(transactionId, response) =>
            Message.PingResponseFormat.read(response).leftMap(e => new Exception(e))
          case other =>
            Left(new Exception(s"Got wrong message $other"))
        }
      )
    } yield iteration(NonEmptyList.one(NodeInfo(r.id, Client.BootstrapNode)))
  }
}

object Client {
  val BootstrapNode = new InetSocketAddress("router.bittorrent.com", 6881)

  def apply[F[_]: Sync](selfId: NodeId, socket: Socket[F]): F[Client[F]] =
    for {
      logger <- Slf4jLogger.fromClass[F](getClass)
    }
    yield new Client(selfId, socket, logger)
}
