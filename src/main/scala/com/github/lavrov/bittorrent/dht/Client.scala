package com.github.lavrov.bittorrent.dht

import java.net.InetSocketAddress

import cats._
import cats.implicits._
import fs2.io.udp.{Packet, Socket}
import com.github.lavrov.bencode.{decode, encode}
import com.github.lavrov.bittorrent.dht.protocol.{Message, Query, Response}
import fs2.Chunk

class Client[F[_]: Monad](selfId: NodeId, socket: Socket[F])(implicit M: MonadError[F, Throwable]) {

  def readMessage: F[Message] =
    for {
      packet <- socket.read()
      decodeResult <- M.fromEither(
        decode(packet.bytes.toArray).left.map(e => new Exception(e.message)))
      message <- M.fromEither(
        Message.MessageFormat.read(decodeResult.value).left.map(e => new Exception(e)))
    } yield message

  def sendMessage(address: InetSocketAddress, message: Message): F[Unit] =
    for {
      bc <- M.fromEither(Message.MessageFormat.write(message).left.map(new Exception(_)))
      bytes = encode(bc)
      _ <- socket.write(Packet(address, Chunk.byteVector(bytes.bytes)))

    } yield ()

  def main: F[Response.Nodes] =
    for {
      _ <- sendMessage(Client.BootstrapNode, Message.QueryMessage("aa", Query.Ping(selfId)))
      m <- readMessage
      _ <- sendMessage(Client.BootstrapNode, Message.QueryMessage("ab", Query.FindNode(selfId, selfId)))
      m1 <- readMessage
      r <- M.fromEither(
        PartialFunction.condOpt(m1){
          case Message.ResponseMessage(transactionId, response) =>
            Message.NodesResponseFormat.read(response).left.map(e => new Exception(e))
        }
        .toRight(new Exception("Got wrong message"))
        .flatten
      )
    } yield r
}

object Client {
  val BootstrapNode = new InetSocketAddress("router.bittorrent.com", 6881)
}
