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
import com.github.lavrov.bittorrent.TorrentMetadata, TorrentMetadata.Info
import com.olegpy.meow.effects._
import fs2.{Pull, Stream}
import fs2.concurrent.Queue
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import monocle.function.At.at
import monocle.macros.GenLens
import scodec.bits.ByteVector

import scala.util.chaining._

case class Downloading[F[_]](
    send: Downloading.Command[F] => F[Unit],
    completePieces: Stream[F, CompletePiece],
    fiber: Fiber[F, Unit]
)

object Downloading {

  case class State[F[_]](
      incompletePieces: Map[Long, IncompletePiece],
      chunkQueue: List[Message.Request] = Nil,
      connections: Map[UUID, Connection[F]] = Map.empty[UUID, Connection[F]],
      inProgress: Map[Message.Request, UUID] = Map.empty,
      inProgressByPeer: Map[UUID, Set[Message.Request]] = Map.empty
  )

  sealed trait Command[F[_]]

  object Command {
    case class Init[F[_]]() extends Command[F]
    case class AddPeer[F[_]](connectionActor: Connection[F]) extends Command[F]
    case class RemovePeer[F[_]](id: UUID) extends Command[F]
    case class AddDownloaded[F[_]](request: Message.Request, bytes: ByteVector) extends Command[F]
  }

  trait Effects[F[_]] {
    def send(command: Command[F]): F[Unit]
    def notifyComplete: F[Unit]
    def notifyPieceComplete(piece: CompletePiece): F[Unit]
  }

  case class CompletePiece(index: Long, begin: Long, bytes: ByteVector)

  case class IncompletePiece(
      index: Long,
      begin: Long,
      size: Long,
      checksum: ByteVector,
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
    def verified: Option[ByteVector] = {
      val joinedChunks: ByteVector = downloaded.toList.sortBy(_._1.begin).map(_._2).reduce(_ ++ _)
      if (joinedChunks.digest("SHA-1") == checksum) joinedChunks.some else none
    }
    def reset: IncompletePiece = copy(downloadedSize = 0, downloaded = Map.empty)
  }

  def start[F[_]: Concurrent](
      metaInfo: TorrentMetadata.Info,
      peers: Stream[F, Connection[F]]
  ): F[Downloading[F]] = {
    for {
      logger <- Slf4jLogger.fromClass(getClass)
      commandQueue <- Queue.unbounded[F, Command[F]]
      incompletePieces = buildQueue(metaInfo)
      requestQueue = incompletePieces.map(_.requests).toList.flatten
      stateRef <- Ref.of[F, State[F]](
        State(incompletePieces.toList.map(p => (p.index, p)).toMap, requestQueue)
      )
      completePieces <- Queue.noneTerminated[F, CompletePiece]
      downloadComplete <- Deferred[F, Unit]
      effects = new Effects[F] {
        def send(command: Command[F]): F[Unit] = commandQueue.enqueue1(command)
        def notifyComplete: F[Unit] = downloadComplete.complete(()) *> completePieces.enqueue1(none)
        def notifyPieceComplete(piece: CompletePiece): F[Unit] = completePieces.enqueue1(piece.some)
      }
      _ <- Concurrent[F] start peers
        .evalTap(c => effects.send(Command.AddPeer(c)))
        .compile
        .drain
      _ <- effects.send(Command.Init())
      behaviour = new Behaviour(16, 10, stateRef.stateInstance, effects, logger)
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

  def buildQueue(metaInfo: TorrentMetadata.Info): Chain[IncompletePiece] = {

    def downloadFile(
        pieceLength: Long,
        totalLength: Long,
        pieces: ByteVector
    ): Chain[IncompletePiece] = {
      var result = Chain.empty[IncompletePiece]
      def loop(index: Long): Unit = {
        val thisPieceLength =
          math.min(pieceLength, totalLength - index * pieceLength)
        if (thisPieceLength > 0) {
          val list =
            downloadPiece(index, thisPieceLength)
          result = result append IncompletePiece(
            index,
            index * pieceLength,
            thisPieceLength,
            pieces.drop(index * 20).take(20),
            list.toList
          )
          loop(index + 1)
        }
      }
      loop(0)
      result
    }

    def downloadPiece(
        pieceIndex: Long,
        length: Long
    ): Chain[Message.Request] = {
      val chunkSize = 16 * 1024
      var result = Chain.empty[Message.Request]
      def loop(index: Long): Unit = {
        val thisChunkSize = math.min(chunkSize, length - index * chunkSize)
        if (thisChunkSize > 0) {
          val begin = index * chunkSize
          result = result append Message.Request(pieceIndex, begin, thisChunkSize)
          loop(index + 1)
        }
      }
      loop(0)
      result
    }

    metaInfo match {
      case info: Info.SingleFile => downloadFile(info.pieceLength, info.length, info.pieces)
      case info: Info.MultipleFiles =>
        downloadFile(info.pieceLength, info.files.map(_.length).sum, info.pieces)
    }
  }

  class Behaviour[F[_]](
      maxInflightChunks: Int,
      maxConnections: Int,
      State: MonadState[F, Downloading.State[F]],
      effects: Effects[F],
      logger: Logger[F]
  )(implicit F: Concurrent[F])
      extends (Command[F] => F[Unit]) {

    object Lenses {
      val incompletePiece = GenLens[State[F]](_.incompletePieces)
    }

    def apply(command: Command[F]): F[Unit] = command match {
      case Command.Init() => init
      case Command.AddPeer(connectionActor) => addPeer(connectionActor)
      case Command.RemovePeer(id) => removePeer(id)
      case Command.AddDownloaded(request, bytes) => addDownloaded(request, bytes)
    }

    def init: F[Unit] = Concurrent[F].unit

    def addPeer(connection: Connection[F]): F[Unit] = {
      for {
        state <- State.get
        uuid = UUID.randomUUID()
        _ <- State.set(
          state.copy(
            connections = state.connections.updated(uuid, connection),
            inProgressByPeer = state.inProgressByPeer.updated(uuid, Set.empty)
          )
        )
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
        _ <- logger.debug(s"Remove peer [$peerId]")
        requests <- State.inspect { state =>
          state.inProgressByPeer.getOrElse(peerId, Set.empty)
        }
        _ <- logger.debug(s"Returned ${requests.size} to the queue")
        _ <- State.modify { state =>
          state.copy(
            connections = state.connections - peerId,
            inProgressByPeer = state.inProgressByPeer - peerId,
            inProgress = state.inProgress -- requests,
            chunkQueue = requests.toList ++ state.chunkQueue
          )
        }
      } yield ()
    }

    def addDownloaded(request: Message.Request, bytes: ByteVector): F[Unit] = {
      val pieceLens = Lenses.incompletePiece composeLens at(request.index)
      for {
        _ <- State.modify { state =>
          val peer = state.inProgress(request)
          val peerRequests = state.inProgressByPeer(peer)
          state.copy(
            inProgress = state.inProgress - request,
            inProgressByPeer = state.inProgressByPeer.updated(peer, peerRequests - request)
          )
        }
        incompletePieceOpt <- State.inspect(pieceLens.get)
        incompletePiece <- F.fromOption(incompletePieceOpt, new Exception("Piece not found"))
        incompletePiece <- incompletePiece.add(request, bytes).pure
        _ <- {
          if (incompletePiece.isComplete)
            incompletePiece.verified.pipe {
              case Some(bytes) =>
                logger.debug(s"Piece ${incompletePiece.index} passed checksum verification") *>
                State.modify(pieceLens set none) *>
                  effects.notifyPieceComplete(
                    CompletePiece(
                      incompletePiece.index,
                      incompletePiece.begin,
                      bytes
                    )
                  )
              case None =>
                logger.warn(s"Piece ${incompletePiece.index} failed checksum verification") *>
                State.modify(pieceLens set incompletePiece.reset.some)
            }
          else
            State.modify(pieceLens.set(incompletePiece.some))
        }
        _ <- tryDownload
      } yield ()
    }

    def tryDownload: F[Unit] = {
      State.inspect(_.chunkQueue).flatMap {
        case Nil =>
          State.inspect(_.inProgress.isEmpty).flatMap {
            case true =>
              effects.notifyComplete
            case false =>
              Monad[F].unit
          }

        case nextChunk :: leftChunks =>
          State
            .inspect(
              state =>
                state.inProgressByPeer.toSeq
                  .sortBy(_._2.size)
                  .headOption
                  .collect {
                    case (peerId, requests) =>
                      val connection = state.connections(peerId)
                      (peerId, connection, requests)
                  }
                  .orElse {
                    state.connections.headOption.map {
                      case (peerId, connection) =>
                        (peerId, connection, Set.empty[Message.Request])
                    }
                  }
            )
            .flatMap {
              case Some((peerId, connection, requests)) if requests.size < maxInflightChunks =>
                for {
                  _ <- State.modify(
                    state =>
                      state.copy(
                        chunkQueue = leftChunks,
                        inProgress = state.inProgress.updated(nextChunk, peerId),
                        inProgressByPeer =
                          state.inProgressByPeer.updated(peerId, requests + nextChunk)
                      )
                  )
                  _ <- connection.download(nextChunk)
                  _ <- tryDownload
                } yield ()
              case _ =>
                Monad[F].unit
            }
      }
    }
  }
}
