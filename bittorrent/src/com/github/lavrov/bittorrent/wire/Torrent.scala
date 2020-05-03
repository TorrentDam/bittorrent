package com.github.lavrov.bittorrent.wire

import cats.effect.concurrent.MVar
import cats.effect.implicits._
import cats.effect.{Concurrent, Resource, Timer}
import cats.implicits._
import com.github.lavrov.bittorrent.MetaInfo
import logstage.LogIO
import scodec.bits.ByteVector

import scala.collection.immutable.BitSet

trait Torrent[F[_]] {
  def getMetaInfo: MetaInfo
  def stats: F[Torrent.Stats]
  def piece(index: Int): F[ByteVector]
}

object Torrent {

  def make[F[_]](
    metaInfo: MetaInfo,
    swarm: Swarm[F]
  )(implicit F: Concurrent[F], timer: Timer[F], logger: LogIO[F]): Resource[F, Torrent[F]] =
    Resource {
      for {
        piecePicker <- PiecePicker(metaInfo.parsed)
        downloadFiber <- Download.download(swarm, piecePicker).start
      } yield {
        val impl =
          new Torrent[F] {
            def getMetaInfo = metaInfo
            def stats: F[Stats] =
              for {
                connected <- swarm.connected.list
                availability <- connected.traverse(_.availability.get)
                availability <- availability.foldMap(identity).pure[F]
              } yield Stats(connected.size, availability)
            def piece(index: Int): F[ByteVector] =
              piecePicker.download(index)
          }
        val close = downloadFiber.cancel
        (impl, close)
      }
    }

  case class Stats(
    connected: Int,
    availability: BitSet
  )

  sealed trait Error extends Exception
  object Error {
    case class EmptyMetadata() extends Error
  }
}
