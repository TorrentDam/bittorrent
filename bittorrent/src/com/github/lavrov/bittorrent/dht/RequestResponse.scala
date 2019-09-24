package com.github.lavrov.bittorrent.dht

import java.net.InetSocketAddress

import cats._
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.syntax.all._
import cats.effect.{Concurrent, Resource, Timer}
import cats.syntax.all._
import com.github.lavrov.bencode.Bencode
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
      receiveMessage: F[(InetSocketAddress, Either[Message.ErrorMessage, Message.ResponseMessage])]
  )(
      implicit
      F: Concurrent[F],
      timer: Timer[F]
  ): Resource[F, RequestResponse[F]] = Resource {
    for {
      ref <- Ref.of[F, Map[ByteVector, Either[Throwable, Response] => F[Unit]]](Map.empty)
      fiber <- {
        def continue(id: ByteVector, result: Either[Throwable, Response]) =
          ref.get.map(_ get id).flatMap {
            case Some(callback) => callback(result)
            case None => F.unit
          }

        receiveLoop(receiveMessage, continue).start
      }
    } yield {
      def receive(transactionId: ByteVector, timeout: FiniteDuration) =
        Deferred.uncancelable[F, Either[Throwable, Response]].flatMap { deferred =>
          val addCallback = ref.update(_.updated(transactionId, deferred.complete))
          val removeCallback = ref.update(_ - transactionId)
          val result = F
            .race(
              deferred.get,
              timer.sleep(timeout).as(Timeout().asLeft)
            )
            .map(_.merge)
            .flatMap(F.fromEither)
          addCallback >> result << removeCallback
        }
      impl(generateTransactionId, sendMessage, receive) -> fiber.cancel
    }
  }

  private def impl[F[_]](
      generateTransactionId: F[ByteVector],
      sendMessage: (InetSocketAddress, Message) => F[Unit],
      receive: (ByteVector, FiniteDuration) => F[Response]
  )(
      implicit F: Monad[F]
  ): RequestResponse[F] = new RequestResponse[F] {
    def sendQuery(address: InetSocketAddress, query: Query): F[Response] = {
      generateTransactionId.flatMap { transactionId =>
        val message = Message.QueryMessage(transactionId, query)
        val send = sendMessage(address, message)
        send >> receive(transactionId, 10.seconds)
      }
    }
  }

  private def receiveLoop[F[_]](
      receive: F[(InetSocketAddress, Either[Message.ErrorMessage, Message.ResponseMessage])],
      continue: (ByteVector, Either[Throwable, Response]) => F[Unit]
  )(
      implicit
      F: Monad[F]
  ): F[Unit] = {
    val step = receive.map(_._2).flatMap {
      case Right(Message.ResponseMessage(transactionId, response)) =>
        continue(transactionId, response.asRight)
      case Left(Message.ErrorMessage(transactionId, details)) =>
        continue(transactionId, ErrorResponse(details).asLeft)
    }
    step.foreverM
  }

  case class ErrorResponse(details: Bencode) extends Throwable
  case class InvalidResponse() extends Throwable
  case class Timeout() extends Throwable
}
