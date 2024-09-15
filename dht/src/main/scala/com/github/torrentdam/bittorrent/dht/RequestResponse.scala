package com.github.torrentdam.bittorrent.dht

import cats.*
import cats.effect.kernel.Deferred
import cats.effect.kernel.Ref
import cats.effect.kernel.Temporal
import cats.effect.syntax.all.*
import cats.effect.Concurrent
import cats.effect.Resource
import cats.syntax.all.*
import com.comcast.ip4s.*
import com.github.torrentdam.bittorrent.dht.RequestResponse.Timeout
import com.github.torrentdam.bencode.Bencode
import scala.concurrent.duration.*
import scodec.bits.ByteVector

trait RequestResponse[F[_]] {
  def sendQuery(address: SocketAddress[IpAddress], query: Query): F[Response]
}

object RequestResponse {

  def make[F[_]](
    generateTransactionId: F[ByteVector],
    sendQuery: (SocketAddress[IpAddress], Message.QueryMessage) => F[Unit],
    receiveMessage: F[
      (SocketAddress[IpAddress], Message.ErrorMessage | Message.ResponseMessage)
    ]
  )(using
    F: Temporal[F]
  ): Resource[F, RequestResponse[F]] =
    Resource {
      for {
        callbackRegistry <- CallbackRegistry.make[F]
        fiber <- receiveLoop(receiveMessage, callbackRegistry.complete).start
      } yield {
        new Impl(generateTransactionId, sendQuery, callbackRegistry.add) -> fiber.cancel
      }
    }

  private class Impl[F[_]](
    generateTransactionId: F[ByteVector],
    sendQueryMessage: (SocketAddress[IpAddress], Message.QueryMessage) => F[Unit],
    receive: ByteVector => F[Either[Throwable, Response]]
  )(using F: MonadError[F, Throwable])
      extends RequestResponse[F] {
    def sendQuery(address: SocketAddress[IpAddress], query: Query): F[Response] = {
      generateTransactionId.flatMap { transactionId =>
        val send = sendQueryMessage(
          address,
          Message.QueryMessage(transactionId, query)
        )
        send >> receive(transactionId).flatMap(F.fromEither)
      }
    }
  }

  private def receiveLoop[F[_]](
    receive: F[
      (SocketAddress[IpAddress], Message.ErrorMessage | Message.ResponseMessage)
    ],
    continue: (ByteVector, Either[Throwable, Response]) => F[Boolean]
  )(using
    F: Monad[F]
  ): F[Unit] = {
    val step = receive.map(_._2).flatMap {
      case Message.ResponseMessage(transactionId, response) =>
        continue(transactionId, response.asRight)
      case Message.ErrorMessage(transactionId, details) =>
        continue(transactionId, ErrorResponse(details).asLeft)
    }
    step.foreverM[Unit]
  }

  case class ErrorResponse(details: Bencode) extends Throwable
  case class InvalidResponse() extends Throwable
  case class Timeout() extends Throwable
}

trait CallbackRegistry[F[_]] {
  def add(transactionId: ByteVector): F[Either[Throwable, Response]]

  def complete(transactionId: ByteVector, result: Either[Throwable, Response]): F[Boolean]
}

object CallbackRegistry {
  def make[F[_]: Temporal]: F[CallbackRegistry[F]] = {
    for {
      ref <-
        Ref
          .of[F, Map[ByteVector, Either[Throwable, Response] => F[Boolean]]](
            Map.empty
          )
    } yield {
      new Impl(ref)
    }
  }

  private class Impl[F[_]](
    ref: Ref[F, Map[ByteVector, Either[Throwable, Response] => F[Boolean]]]
  )(using F: Temporal[F])
      extends CallbackRegistry[F] {
    def add(transactionId: ByteVector): F[Either[Throwable, Response]] = {
      F.deferred[Either[Throwable, Response]].flatMap { deferred =>
        val update =
          ref.update { map =>
            map.updated(transactionId, deferred.complete)
          }
        val delete =
          ref.update { map =>
            map - transactionId
          }
        (update *> deferred.get).guarantee(delete)
      }
    }

    def complete(transactionId: ByteVector, result: Either[Throwable, Response]): F[Boolean] =
      ref.get.flatMap { map =>
        map.get(transactionId) match {
          case Some(callback) => callback(result)
          case None           => false.pure[F]
        }
      }
  }
}
