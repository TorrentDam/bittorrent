package com.github.lavrov.bittorrent.protocol

import java.util.UUID

import cats.Monad
import cats.data.Chain
import cats.effect.concurrent.{Deferred, MVar, Ref}
import cats.effect.{Concurrent, Fiber}
import cats.mtl.MonadState
import cats.syntax.all._
import com.github.lavrov.bittorrent.protocol.Downloading.CompletePiece
import com.github.lavrov.bittorrent.protocol.message.Message
import com.github.lavrov.bittorrent.protocol.message.Message.Request
import com.github.lavrov.bittorrent.{Info, MetaInfo, PeerInfo}
import com.olegpy.meow.effects._
import fs2.{Pull, Stream}
import fs2.concurrent.Queue
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import monocle.function.At.at
import monocle.macros.GenLens
import scodec.bits.ByteVector

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
    def requestPeer: F[Unit]
  }

  case class CompletePiece(index: Long, begin: Long, bytes: ByteVector)

  case class IncompletePiece(
      index: Long,
      begin: Long,
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

  def start[F[_]: Concurrent](metaInfo: MetaInfo, peers: Stream[F, Connection[F]]): F[Downloading[F]] = {
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
      peerDemand <- MVar.empty[F, Int]
      throttledPeers: Stream[F, Connection[F]] = {
        def recur(source: Stream[F, Connection[F]]): Pull[F, Connection[F], Unit] =
          Pull.eval(peerDemand.take) >>
          Pull.eval(logger.info("Request a new connection")) >>
          source.pull.uncons1.flatMap {
            case Some((connection, tail)) => Pull.output1(connection) >> recur(tail)
            case None => Pull.done
          }
        recur(peers).stream
      }
      effects = new Effects[F] {
        def send(command: Command[F]): F[Unit] = commandQueue.enqueue1(command)
        def notifyComplete: F[Unit] = downloadComplete.complete(()) *> completePieces.enqueue1(none)
        def notifyPieceComplete(piece: CompletePiece): F[Unit] = completePieces.enqueue1(piece.some)
        def requestPeer: F[Unit] = Concurrent[F].start { peerDemand.put(1) }.void
      }
      _ <- Concurrent[F] start throttledPeers.evalTap(c => effects.send(Command.AddPeer(c))).compile.drain
      _ <- effects.send(Command.Init())
      behaviour = new Behaviour(16, 3, stateRef.stateInstance, effects, logger)
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
        val thisPieceLength =
          math.min(fileInfo.pieceLength, fileInfo.length - index * fileInfo.pieceLength)
        if (thisPieceLength > 0) {
          val list =
            downloadPiece(index, thisPieceLength, fileInfo.pieces.drop(index * 20).take(20))
          result = result append IncompletePiece(
            index,
            index * fileInfo.pieceLength,
            thisPieceLength,
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
        length: Long,
        checksum: ByteVector
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

    metaInfo.info match {
      case fileInfo: Info.SingleFile => downloadFile(fileInfo)
      case _ => ???
    }
  }

  class Behaviour[F[_]: Concurrent](
      maxInflightChunks: Int,
      maxConnections: Int,
      State: MonadState[F, Downloading.State[F]],
      effects: Effects[F],
      logger: Logger[F]
  ) extends (Command[F] => F[Unit]) {

    object Lenses {
      val incompletePiece = GenLens[State[F]](_.incompletePieces)
    }

    def apply(command: Command[F]): F[Unit] = command match {
      case Command.Init() => init
      case Command.AddPeer(connectionActor) => addPeer(connectionActor)
      case Command.RemovePeer(id) => removePeer(id)
      case Command.AddDownloaded(request, bytes) => addDownloaded(request, bytes)
    }

    def init: F[Unit] = {
      effects.requestPeer
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
        _ <- requestPeer
        _ <- tryDownload
      } yield ()
    }

    def removePeer(peerId: UUID): F[Unit] = {
      for {
        _ <- logger.debug(s"Remove peer [$peerId]")
        requests <- State.inspect { state => state.inProgressByPeer.getOrElse(peerId, Set.empty) }
        _ <- logger.debug(s"Returned ${requests.size} to the queue")
        _ <- State.modify { state =>
          state.copy(
            connections = state.connections - peerId,
            inProgressByPeer = state.inProgressByPeer - peerId,
            inProgress = state.inProgress -- requests,
            chunkQueue = requests.toList ++ state.chunkQueue,
          )
        }
        _ <- requestPeer
      } yield ()
    }

    def requestPeer: F[Unit] = State.inspect(_.connections.size < maxConnections) >>= effects.requestPeer.whenA

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
        incompletePiece <- Concurrent[F]
          .fromOption(incompletePieceOpt, new Exception("Piece not found"))
        incompletePiece <- Monad[F] pure incompletePiece.add(request, bytes)
        _ <- {
          if (incompletePiece.isComplete)
            State.modify(pieceLens.set(None)) *>
              effects.notifyPieceComplete(
                CompletePiece(
                  incompletePiece.index,
                  incompletePiece.begin,
                  incompletePiece.joinChunks
                )
              )
          else
            State.modify(pieceLens.set(incompletePiece.some))
        }
        _ <- tryDownload
      } yield ()
    }

    def tryDownload: F[Unit] = {
      State.inspect(_.inProgress.size < maxInflightChunks).flatMap {
        case false =>
          Monad[F].unit
        case true =>
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
                      .sortBy(_._2.size)(Ordering.Int.reverse)
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
                  case None =>
                    Monad[F].unit
                  case Some((peerId, connection, requests)) =>
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
                      _ <- connection send Connection.Command.Download(nextChunk)
                      _ <- tryDownload
                    } yield ()
                }
          }
      }
    }
  }
}
