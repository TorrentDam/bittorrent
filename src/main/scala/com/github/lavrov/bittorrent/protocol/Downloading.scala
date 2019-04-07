package com.github.lavrov.bittorrent.protocol

import java.util.UUID

import cats.Monad
import cats.data.Chain
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.{Concurrent, Fiber}
import cats.mtl.MonadState
import cats.syntax.all._
import com.github.lavrov.bittorrent.protocol.message.Message
import com.github.lavrov.bittorrent.{Info, MetaInfo}
import com.olegpy.meow.effects._
import fs2.concurrent.Queue
import scodec.bits.ByteVector

case class Downloading[F[_]](
    send: Downloading.Command[F] => F[Unit],
    fiber: Fiber[F, Unit],
    complete: F[Unit]
)

object Downloading {

  case class State[F[_]](
      chunkQueue: List[Message.Request],
      connections: Map[UUID, Connection[F]] = Map.empty[UUID, Connection[F]],
      inProgress: Map[Message.Request, UUID] = Map.empty,
      downloaded: Set[Message.Request] = Set.empty
  )

  sealed trait Command[F[_]]

  object Command {
    case class AddPeer[F[_]](connectionActor: Connection[F]) extends Command[F]
    case class RemovePeer[F[_]](id: UUID) extends Command[F]
    case class AddDownloaded[F[_]](request: Message.Request, bytes: ByteVector) extends Command[F]
  }

  trait Effects[F[_]] {
    def send(command: Command[F]): F[Unit]
    def notifyComplete: F[Unit]
  }

  def start[F[_]: Concurrent](metaInfo: MetaInfo): F[Downloading[F]] = {
    for {
      _ <- Monad[F].unit
      commandQueue <- Queue.unbounded[F, Command[F]]
      requestQueue = buildQueue(metaInfo).toList
      stateRef <- Ref.of[F, State[F]](State(requestQueue))
      downloadComplete <- Deferred[F, Unit]
      effects = new Effects[F] {
        def send(command: Command[F]): F[Unit] = commandQueue.enqueue1(command)
        def notifyComplete: F[Unit] = downloadComplete.complete(())
      }
      behaviour = new Behaviour(stateRef.stateInstance, effects)
      fiber <- Concurrent[F].start {
        commandQueue.dequeue.evalTap(behaviour).compile.drain
      }
    } yield new Downloading(commandQueue.enqueue1, fiber, downloadComplete.get)
  }

  def buildQueue(metaInfo: MetaInfo): Chain[Message.Request] = {

    def downloadFile(fileInfo: Info.SingleFile): Chain[Message.Request] = {
      var result = Chain.empty[Message.Request]
      def loop(index: Long): Unit = {
        val pieceLength =
          math.min(fileInfo.pieceLength, fileInfo.length - index * fileInfo.pieceLength)
        if (pieceLength != 0) {
          val list = downloadPiece(index, pieceLength, fileInfo.pieces.drop(index * 20).take(20))
          result = result ++ list
          loop(index + 1)
        }
      }
      loop(0)
      result
    }

    def downloadPiece(
        pieceIndex: Long,
        length: Long,
        checksum: ByteVector
    ): Chain[Message.Request] = {
      val maxChunkSize = 16 * 1024
      var result = Chain.empty[Message.Request]
      def loop(index: Long): Unit = {
        val chunkSize = math.min(maxChunkSize, length - index * maxChunkSize)
        if (chunkSize != 0) {
          val begin = index * maxChunkSize
          result = result append Message.Request(pieceIndex, begin, chunkSize)
          loop(index + 1)
        }
      }
      loop(0)
      result
    }

    metaInfo.info match {
      case fileInfo: Info.SingleFile => downloadFile(fileInfo)
      case _ => ???
    }
  }

  class Behaviour[F[_]: Concurrent](
      State: MonadState[F, Downloading.State[F]],
      effects: Effects[F]
  ) extends (Command[F] => F[Unit]) {

    def apply(command: Command[F]): F[Unit] = command match {
      case Command.AddPeer(connectionActor) => addPeer(connectionActor)
      case Command.RemovePeer(id) => removePeer(id)
      case Command.AddDownloaded(request, bytes) => addDownloaded(request, bytes)
    }

    def addPeer(connection: Connection[F]): F[Unit] = {
      for {
        state <- State.get
        uuid = UUID.randomUUID()
        _ <- State.set(state.copy(connections = state.connections.updated(uuid, connection)))
        _ <- Concurrent[F].start {
          connection.events
            .evalTap {
              case Connection.Event.Downloaded(request, bytes) =>
                effects.send(Command.AddDownloaded(request, bytes))
              case _ =>
                Monad[F].unit
            }
            .onFinalize {
              effects.send(Command.RemovePeer(uuid))
            }
            .compile
            .drain
        }
        _ <- tryDownload
      } yield ()
    }

    def removePeer(peerId: UUID): F[Unit] = {
      for {
        state <- State.get
        (chunkId, _) = state.inProgress.find(_._2 == peerId).get
        _ <- State.set(
          state.copy(
            inProgress = state.inProgress - chunkId,
            chunkQueue = chunkId :: state.chunkQueue
          )
        )
      } yield ()
    }

    def addDownloaded(request: Message.Request, bytes: ByteVector): F[Unit] = {
      for {
        _ <- State.modify(state => state.copy(inProgress = state.inProgress - request))
        _ <- State.modify(state => state.copy(downloaded = state.downloaded + request))
        _ <- tryDownload
      } yield ()
    }

    def tryDownload: F[Unit] = {
      State.inspect(_.inProgress.size < 128).flatMap {
        case true =>
          for {
            chunkFromQueue <- State.inspect(_.chunkQueue).map {
              case head :: tail => Some(head -> tail)
              case Nil => None
            }
            peerIdOpt <- State.inspect(_.connections.headOption) // TODO
            _ <- (chunkFromQueue, peerIdOpt) match {
              case (Some((nextChunk, rest)), Some((peerId, connection))) =>
                for {
                  _ <- State.modify(
                    state =>
                      state.copy(
                        chunkQueue = rest,
                        inProgress = state.inProgress.updated(nextChunk, peerId)
                      )
                  )
                  _ <- connection send Connection.Command.Download(nextChunk)
                  _ <- tryDownload
                } yield ()
              case _ =>
                State.inspect(_.inProgress.isEmpty).flatMap {
                  case true =>
                    effects.notifyComplete
                  case false =>
                    Monad[F].unit
                }
            }
          } yield ()
        case false =>
          Monad[F].unit
      }
    }

  }
}
