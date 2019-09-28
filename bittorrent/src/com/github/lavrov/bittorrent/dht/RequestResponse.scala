package com.github.lavrov.bittorrent.dht

import java.net.InetSocketAddress

import cats._
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.syntax.all._
import cats.effect.{Async, Concurrent, Resource, Timer}
import cats.syntax.all._
import com.github.lavrov.bencode.Bencode
import com.github.lavrov.bittorrent.dht.RequestResponse.Timeout
import com.github.lavrov.bittorrent.dht.message.{Message, Query, Response}
import scodec.bits.ByteVector

import scala.concurrent.duration._

trait RequestResponse[F[_]] {
  def sendQuery(address: InetSocketAddress, query: Query): F[Response]
}

object RequestResponse {

  def make[F[_]](
    generateTransactionId: F[ByteVector],
    sendMessage: (InetSocketAddress, Message) => F[Unit],
    receiveMessage: F[
      (InetSocketAddress, Either[Message.ErrorMessage, Message.ResponseMessage])
    ]
  )(implicit
    F: Concurrent[F],
    timer: Timer[F]): Resource[F, RequestResponse[F]] = Resource {
    for {
      callbackRegistry <- CallbackRegistry.make[F]
      fiber <- receiveLoop(receiveMessage, callbackRegistry.complete).start
    } yield {
      new Impl(generateTransactionId, sendMessage, callbackRegistry.add) -> fiber.cancel
    }
  }

  private class Impl[F[_]](
    generateTransactionId: F[ByteVector],
    sendMessage: (InetSocketAddress, Message) => F[Unit],
    receive: (ByteVector, FiniteDuration) => F[Either[Throwable, Response]]
  )(implicit F: MonadError[F, Throwable])
      extends RequestResponse[F] {
    def sendQuery(address: InetSocketAddress, query: Query): F[Response] = {
      generateTransactionId.flatMap { transactionId =>
        val message = Message.QueryMessage(transactionId, query)
        val send = sendMessage(address, message)
        send >> receive(transactionId, 10.seconds).flatMap(F.fromEither)
      }
    }
  }

  private def receiveLoop[F[_]](
    receive: F[
      (InetSocketAddress, Either[Message.ErrorMessage, Message.ResponseMessage])
    ],
    continue: (ByteVector, Either[Throwable, Response]) => F[Boolean]
  )(implicit
    F: Monad[F]): F[Unit] = {
    val step = receive.map(_._2).flatMap {
      case Right(Message.ResponseMessage(transactionId, response)) =>
        continue(transactionId, response.asRight)
      case Left(Message.ErrorMessage(transactionId, details)) =>
        continue(transactionId, ErrorResponse(details).asLeft)
    }
    step.foreverM[Unit]
  }

  case class ErrorResponse(details: Bencode) extends Throwable
  case class InvalidResponse() extends Throwable
  case class Timeout() extends Throwable
}

trait CallbackRegistry[F[_]] {
  def add(transactionId: ByteVector,
          timeout: FiniteDuration): F[Either[Throwable, Response]]

  def complete(transactionId: ByteVector,
               result: Either[Throwable, Response]): F[Boolean]
}

object CallbackRegistry {
  def make[F[_]: Concurrent: Timer]: F[CallbackRegistry[F]] = {
    for {
      ref <- Ref
        .of[F, Map[ByteVector, Either[Throwable, Response] => F[Boolean]]](
          Map.empty
        )
    } yield {
      new Impl(ref)
    }
  }

  private class Impl[F[_]: Concurrent: Timer](
    ref: Ref[F, Map[ByteVector, Either[Throwable, Response] => F[Boolean]]]
  ) extends CallbackRegistry[F] {
    def add(transactionId: ByteVector,
            timeout: FiniteDuration): F[Either[Throwable, Response]] =
      Deferred.uncancelable[F, Either[Throwable, Response]].flatMap {
        deferred =>
          val update =
            ref.update { map =>
              map.updated(
                transactionId,
                deferred.complete(_).attempt.map(_.isRight)
              )
            }
          val scheduleTimeout =
            (Timer[F].sleep(timeout) >> complete(
              transactionId,
              Timeout().asLeft
            )).start
          val delete =
            ref.update { map =>
              map - transactionId
            }
          update *> scheduleTimeout *> deferred.get <* delete
      }

    def complete(transactionId: ByteVector,
                 result: Either[Throwable, Response]): F[Boolean] =
      ref.get.flatMap { map =>
        map.get(transactionId) match {
          case Some(callback) => callback(result)
          case None           => false.pure[F]
        }
      }
  }
}
