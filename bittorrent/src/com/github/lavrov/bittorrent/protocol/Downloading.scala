package com.github.lavrov.bittorrent.protocol

import java.util.UUID

import cats.Monad
import cats.MonadError
import cats.data.Chain
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.{Concurrent, Fiber}
import cats.mtl.MonadState
import cats.syntax.all._
import com.github.lavrov.bittorrent.protocol.Downloading.CompletePiece
import com.github.lavrov.bittorrent.protocol.message.Message
import com.github.lavrov.bittorrent.protocol.message.Message.Request
import com.github.lavrov.bittorrent.TorrentMetadata, TorrentMetadata.Info
import com.github.lavrov.bittorrent.protocol.Downloading.Command.RedispatchRequest
import com.olegpy.meow.effects._
import fs2.{Pull, Stream}
import fs2.concurrent.Queue
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import monocle.function.At.at
import monocle.macros.GenLens
import scodec.bits.ByteVector

import scala.concurrent.duration._
import scala.util.chaining._
import cats.effect.Timer
import com.github.lavrov.bittorrent.protocol.Downloading.Command.UpdateChokeStatus
import cats.Parallel
import cats.data.NonEmptyList

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
      activeConnections: Set[UUID] = Set.empty,
      inProgress: Map[Message.Request, Set[UUID]] = Map.empty,
      inProgressByPeer: Map[UUID, Set[Message.Request]] = Map.empty
  )

  sealed trait Command[F[_]]

  object Command {
    case class AddPeer[F[_]](connectionActor: Connection[F]) extends Command[F]
    case class RemovePeer[F[_]](id: UUID) extends Command[F]
    case class UpdateChokeStatus[F[_]](id: UUID, choked: Boolean) extends Command[F]
    case class RedispatchRequest[F[_]](request: Request) extends Command[F]
    case class AddDownloaded[F[_]](peerId: UUID, request: Message.Request, bytes: ByteVector)
        extends Command[F]
  }

  trait Effects[F[_]] {
    def send(command: Command[F]): F[Unit]
    def notifyComplete: F[Unit]
    def notifyPieceComplete(piece: CompletePiece): F[Unit]
    def state: MonadState[F, Downloading.State[F]]
    def schedule(after: FiniteDuration, command: Command[F]): F[Unit]
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

  def start[F[_], F1[_]](
      metaInfo: TorrentMetadata.Info,
      peers: Stream[F, Connection[F]]
  )(implicit F: Concurrent[F], P: Parallel[F, F1], timer: Timer[F]): F[Downloading[F]] = {
    for {
      logger <- Slf4jLogger.fromClass(getClass)
      commandQueue <- Queue.unbounded[F, Command[F]]
      incompletePieces = buildQueue(metaInfo)
      requestQueue = incompletePieces.map(_.requests).toList.flatten
      completePieces <- Queue.noneTerminated[F, CompletePiece]
      stateRef <- Ref.of[F, State[F]](
        State(incompletePieces.toList.map(p => (p.index, p)).toMap, requestQueue)
      )
      effects = new Effects[F] {
        def send(command: Command[F]): F[Unit] = commandQueue.enqueue1(command)
        def notifyComplete: F[Unit] = completePieces.enqueue1(none)
        def notifyPieceComplete(piece: CompletePiece): F[Unit] = completePieces.enqueue1(piece.some)
        val state = stateRef.stateInstance
        def schedule(after: FiniteDuration, command: Command[F]): F[Unit] =
          F.start(timer.sleep(after) *> commandQueue.enqueue1(command)).void
      }
      behaviour = new Behaviour(16, 10, effects, logger)
      getConnectionStream = peers
        .evalTap(c => commandQueue.enqueue1(Command.AddPeer(c)))
        .flatMap { peer =>
          val onDisconnect =
            Stream
              .eval(peer.disconnected *> commandQueue.enqueue1(Command.RemovePeer(peer.uniqueId)))
              .spawn
          val onEvent =
            peer.events.evalTap {
              case Connection.Event.Downloaded(request, bytes) =>
                commandQueue.enqueue1(Command.AddDownloaded(peer.uniqueId, request, bytes))
              case _ =>
                Monad[F].unit
            }
          val onChokedStatusChanged =
            peer.choked.evalTap(
              choked => commandQueue.enqueue1(Command.UpdateChokeStatus(peer.uniqueId, choked))
            )
          onDisconnect.spawn *> onEvent.spawn *> onChokedStatusChanged.spawn
        }
      fiber <- Concurrent[F].start {
        (
          commandQueue.dequeue.evalTap(behaviour).compile.drain,
          getConnectionStream.compile.drain
        ).parTupled.void
      }
    } yield Downloading(
      commandQueue.enqueue1,
      completePieces.dequeue.concurrently(Stream.eval(fiber.join)),
      fiber
    )
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
      effects: Effects[F],
      logger: Logger[F]
  )(implicit F: MonadError[F, Throwable])
      extends (Command[F] => F[Unit]) {

    object Lenses {
      val incompletePiece = GenLens[State[F]](_.incompletePieces)
    }

    def apply(command: Command[F]): F[Unit] = command match {
      case Command.AddPeer(connectionActor) => addPeer(connectionActor)
      case Command.RemovePeer(id) => removePeer(id)
      case UpdateChokeStatus(id, choked) => updateChokeStatus(id, choked)
      case RedispatchRequest(request) => redispatchRequest(request)
      case Command.AddDownloaded(peerId, request, bytes) => addDownloaded(peerId, request, bytes)
    }

    def addPeer(connection: Connection[F]): F[Unit] = {
      for {
        state <- effects.state.get
        _ <- effects.state.set(
          state.copy(
            connections = state.connections.updated(connection.uniqueId, connection),
            inProgressByPeer = state.inProgressByPeer.updated(connection.uniqueId, Set.empty)
          )
        )
        _ <- connection.interested
        _ <- logger.debug(s"Added peer ${connection.uniqueId} ${connection.info}")
      } yield ()
    }

    def removePeer(peerId: UUID): F[Unit] = {
      for {
        _ <- logger.debug(s"Removing peer $peerId")
        requests <- effects.state.inspect { state =>
          state.inProgressByPeer.getOrElse(peerId, Set.empty)
        }
        _ <- effects.state.modify { state =>
          state.copy(
            connections = state.connections - peerId,
            activeConnections = state.activeConnections - peerId,
            inProgressByPeer = state.inProgressByPeer - peerId,
            inProgress = state.inProgress -- requests,
            chunkQueue = requests.toList ++ state.chunkQueue
          )
        }
        _ <- logger.debug(s"Removed peer $peerId")
        _ <- logger.debug(s"Returned ${requests.size} to the queue")
      } yield ()
    }

    def updateChokeStatus(peerId: UUID, choked: Boolean): F[Unit] = {
      for {
        connectionExits <- effects.state.inspect(_.connections.contains(peerId))
        _ <- F.whenA(connectionExits)(
          effects.state.modify(
            state =>
              state.copy(
                activeConnections =
                  if (choked)
                    state.activeConnections - peerId
                  else
                    state.activeConnections + peerId
              )
          ) *>
            logger.debug(s"Peer $peerId is now ${if (choked) "inactive" else "active"}") *>
            F.whenA(!choked)(tryDownload)
        )
      } yield ()
    }

    def redispatchRequest(request: Request): F[Unit] = {
      for {
        inProgress <- effects.state.inspect(_.inProgress.contains(request))
        _ <- F.whenA(inProgress)(
          logger.debug(s"Redispatching $request") *>
            effects.state.modify(
              state =>
                state.copy(
                  chunkQueue = request :: state.chunkQueue
                )
            ) *>
            tryDownload
        )
      } yield ()
    }

    def addDownloaded(peerId: UUID, request: Message.Request, bytes: ByteVector): F[Unit] = {
      import cats.instances.list.catsStdTraverseFilterForList
      val incompletePieceLens = Lenses.incompletePiece composeLens at(request.index)
      effects.state.inspect(_.inProgress.contains(request)).flatMap { stillInProggress =>
        F.whenA(stillInProggress)(
          for {
            _ <- logger.debug(s"Received $request from $peerId")
            otherPeers <- effects.state.inspect { state =>
              val peerIds = state.inProgress(request) - peerId
              state.connections.filterKeys(peerIds).values.toList
            }
            _ <- effects.state.modify { state =>
              val peers = state.inProgress(request)
              state.copy(
                inProgress = state.inProgress - request,
                chunkQueue = state.chunkQueue.filterNot(_ == request),
                inProgressByPeer = peers.foldLeft(state.inProgressByPeer) { (map, peer) =>
                  val peerRequests = map(peer)
                  state.inProgressByPeer.updated(peer, peerRequests - request)
                }
              )
            }
            _ <- catsStdTraverseFilterForList.traverse.traverse_(otherPeers) { connection =>
              logger.debug(s"Cancel $request for ${connection.info}") *>
                connection.cancel(request)
            }
            incompletePieceOpt <- effects.state.inspect(incompletePieceLens.get)
            incompletePiece <- F
              .fromOption(incompletePieceOpt, new Exception(s"Piece ${request.index} not found"))
            incompletePiece <- incompletePiece.add(request, bytes).pure
            _ <- {
              if (incompletePiece.isComplete)
                incompletePiece.verified.pipe {
                  case Some(bytes) =>
                    logger.debug(s"Piece ${incompletePiece.index} passed checksum verification") *>
                      effects.state.modify(incompletePieceLens set none) *>
                      effects.notifyPieceComplete(
                        CompletePiece(
                          incompletePiece.index,
                          incompletePiece.begin,
                          bytes
                        )
                      )
                  case None =>
                    logger.warn(s"Piece ${incompletePiece.index} failed checksum verification") *>
                      effects.state.modify(incompletePieceLens set incompletePiece.reset.some)
                }
              else
                effects.state.modify(incompletePieceLens.set(incompletePiece.some))
            }
            _ <- tryDownload
          } yield ()
        )
      }
    }

    def tryDownload: F[Unit] = {
      effects.state.inspect(_.chunkQueue).flatMap {
        case Nil =>
          effects.state.inspect(_.inProgress.isEmpty).flatMap {
            case true =>
              effects.notifyComplete
            case false =>
              Monad[F].unit
          }

        case nextChunk :: leftChunks =>
          effects.state
            .inspect(
              state =>
                state.inProgressByPeer
                  .filterKeys(state.activeConnections)
                  .toSeq
                  .sortBy(_._2.size)
                  .headOption
                  .map {
                    case (peerId, requests) =>
                      val connection = state.connections(peerId)
                      (peerId, connection, requests)
                  }
                  .orElse {
                    state.connections
                      .filterKeys(state.activeConnections)
                      .headOption
                      .map {
                        case (peerId, connection) =>
                          (peerId, connection, Set.empty[Message.Request])
                      }
                  }
            )
            .flatMap {
              case Some((peerId, connection, requests)) if requests.size < maxInflightChunks =>
                for {
                  _ <- effects.state.modify(
                    state =>
                      state.copy(
                        chunkQueue = leftChunks,
                        inProgress = state.inProgress.updated(
                          nextChunk,
                          state.inProgress.getOrElse(nextChunk, Set.empty) + peerId
                        ),
                        inProgressByPeer =
                          state.inProgressByPeer.updated(peerId, requests + nextChunk)
                      )
                  )
                  _ <- connection.request(nextChunk)
                  _ <- effects.schedule(10.seconds, Command.RedispatchRequest(nextChunk))
                  _ <- logger.debug(s"Requested $nextChunk via $peerId")
                  _ <- tryDownload
                } yield ()
              case _ =>
                Monad[F].unit
            }
      }
    }
  }
}
