package com.github.lavrov.bittorrent.protocol

import java.util.UUID

import cats.Monad
import cats.data.Chain
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.{Concurrent, Fiber}
import cats.mtl.MonadState
import cats.syntax.all._
import com.github.lavrov.bittorrent.protocol.Downloading.CompletePiece
import com.github.lavrov.bittorrent.protocol.message.Message
import com.github.lavrov.bittorrent.protocol.message.Message.Request
import com.github.lavrov.bittorrent.{Info, MetaInfo}
import com.olegpy.meow.effects._
import fs2.Stream
import fs2.concurrent.Queue
import monocle.macros.GenLens
import monocle.function.At.at
import monocle.std.map._
import scodec.bits.ByteVector

case class Downloading[F[_]](
    send: Downloading.Command[F] => F[Unit],
    completePieces: Stream[F, CompletePiece],
    fiber: Fiber[F, Unit]
)

object Downloading {

  case class State[F[_]](
      incompletePieces: Map[Long, IncompletePiece] = Map.empty,
      chunkQueue: List[Message.Request],
      connections: Map[UUID, Connection[F]] = Map.empty[UUID, Connection[F]],
      inProgress: Map[Message.Request, UUID] = Map.empty
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
    def notifyPieceComplete(piece: CompletePiece): F[Unit]
  }

  case class CompletePiece(index: Long, bytes: ByteVector)

  case class IncompletePiece(
      index: Long,
      size: Long,
      requests: List[Message.Request],
      downloadedSize: Long = 0,
      downloaded: Map[Message.Request, ByteVector] = Map.empty
  ) {
    def add(request: Request, bytes: ByteVector): IncompletePiece =
      copy(
        downloadedSize = downloadedSize + request.length,
        downloaded = downloaded.updated(request, bytes)
      )
    def isComplete: Boolean = size == downloadedSize
    def joinChunks: ByteVector = downloaded.toList.sortBy(_._1.begin).map(_._2).reduce(_ ++ _)
  }

  def start[F[_]: Concurrent](metaInfo: MetaInfo): F[Downloading[F]] = {
    for {
      _ <- Monad[F].unit
      commandQueue <- Queue.unbounded[F, Command[F]]
      incompletePieces = buildQueue(metaInfo)
      requestQueue = incompletePieces.map(_.requests).toList.flatten
      stateRef <- Ref.of[F, State[F]](
        State(incompletePieces.toList.map(p => (p.index, p)).toMap, requestQueue)
      )
      completePieces <- Queue.unbounded[F, CompletePiece]
      downloadComplete <- Deferred[F, Unit]
      effects = new Effects[F] {
        def send(command: Command[F]): F[Unit] = commandQueue.enqueue1(command)
        def notifyComplete: F[Unit] = downloadComplete.complete(())
        def notifyPieceComplete(piece: CompletePiece): F[Unit] = completePieces.enqueue1(piece)
      }
      behaviour = new Behaviour(stateRef.stateInstance, effects)
      fiber <- Concurrent[F] start {
        Concurrent[F]
          .race(
            commandQueue.dequeue.evalTap(behaviour).compile.drain,
            downloadComplete.get
          )
          .void
      }
    } yield Downloading(commandQueue.enqueue1, completePieces.dequeue, fiber)
  }

  def buildQueue(metaInfo: MetaInfo): Chain[IncompletePiece] = {

    def downloadFile(fileInfo: Info.SingleFile): Chain[IncompletePiece] = {
      var result = Chain.empty[IncompletePiece]
      def loop(index: Long): Unit = {
        val pieceLength =
          math.min(fileInfo.pieceLength, fileInfo.length - index * fileInfo.pieceLength)
        if (pieceLength != 0) {
          val list = downloadPiece(index, pieceLength, fileInfo.pieces.drop(index * 20).take(20))
          result = result append IncompletePiece(index, pieceLength, list.toList)
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

    object Lenses {
      val incompletePiece = GenLens[State[F]](_.incompletePieces)
    }

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
      val pieceLens = Lenses.incompletePiece composeLens at(request.index)
      for {
        _ <- State.modify(state => state.copy(inProgress = state.inProgress - request))
        incompletePieceOpt <- State.inspect(pieceLens.get)
        incompletePiece <- Concurrent[F]
          .fromOption(incompletePieceOpt, new Exception("Piece not found"))
        incompletePiece <- Monad[F] pure incompletePiece.add(request, bytes)
        _ <- {
          if (incompletePiece.isComplete)
            State.modify(pieceLens.set(None)) *>
              effects.notifyPieceComplete(
                CompletePiece(incompletePiece.index, incompletePiece.joinChunks)
              )
          else
            State.modify(pieceLens.set(incompletePiece.some))
        }
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
