package com.github.torrentdam.bittorrent.dht

import cats.effect.*
import cats.effect.syntax.all.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import com.github.torrentdam.bencode.Bencode
import scodec.bits.ByteVector

trait RpcClient:
  def call(address: SocketAddress[IpAddress], query: Query): IO[Response]

object RpcClient:

  def make(
    generateTransactionId: IO[ByteVector],
    sendQuery: (SocketAddress[IpAddress], Message.QueryMessage) => IO[Unit],
    receiveMessage: IO[Message.ErrorMessage | Message.ResponseMessage]
  ): Resource[IO, RpcClient] =
    for {
      pending <- IO.delay {
        new java.util.concurrent.ConcurrentHashMap[ByteVector, Either[Throwable, Response] => Unit]()
      }.toResource
      _ <- receiveLoop(receiveMessage, pending).background
    } yield Impl(generateTransactionId, sendQuery, pending)

  private class Impl(
    generateTransactionId: IO[ByteVector],
    sendQueryMessage: (SocketAddress[IpAddress], Message.QueryMessage) => IO[Unit],
    pending: java.util.concurrent.ConcurrentHashMap[ByteVector, Either[Throwable, Response] => Unit]
  ) extends RpcClient:
    def call(address: SocketAddress[IpAddress], query: Query): IO[Response] =
      generateTransactionId.flatMap { transactionId =>
        sendQueryMessage(address, Message.QueryMessage(transactionId, query)) *>
        IO.async[Response] { cb =>
          IO.delay {
            pending.put(transactionId, cb)
            Some(IO.delay(pending.remove(transactionId)).void)
          }
        }
      }
  end Impl

  private def receiveLoop(
    receive: IO[Message.ErrorMessage | Message.ResponseMessage],
    pending: java.util.concurrent.ConcurrentHashMap[ByteVector, Either[Throwable, Response] => Unit]
  ): IO[Unit] =

    val step = receive.flatMap:
      case Message.ResponseMessage(transactionId, response) =>
        IO.delay(pending.remove(transactionId)).flatMap {
          case null => IO.unit
          case cb   => IO.delay(cb(response.asRight))
        }
      case Message.ErrorMessage(transactionId, details) =>
        IO.delay(pending.remove(transactionId)).flatMap {
          case null => IO.unit
          case cb   => IO.delay(cb(RpcError(details).asLeft))
        }
    step.foreverM

  end receiveLoop

  case class RpcError(details: Bencode) extends Throwable

end RpcClient
