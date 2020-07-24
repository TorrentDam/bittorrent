import cats.implicits._
import cats.mtl.implicits._
import cats.Monad
import cats.data.StateT
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.mtl.MonadState
import com.github.lavrov.bittorrent.{InfoHash, MetaInfo}

import scala.collection.immutable.ListMap

trait MetadataRegistry[F[_]] {

  def recent: F[Iterable[(InfoHash, MetaInfo)]]

  def put(infoHash: InfoHash, metaInfo: MetaInfo): F[Unit]
}

object MetadataRegistry {

  type State = ListMap[InfoHash, MetaInfo]
  object State {
    val empty: State = ListMap.empty
  }

  def apply[F[_]: Sync](): F[MetadataRegistry[F]] =
    for {
      ref <- Ref.of[F, State](State.empty)
    } yield {

      new MetadataRegistry[F] {

        def recent: F[Iterable[(InfoHash, MetaInfo)]] =
          ref.get.widen

        def put(infoHash: InfoHash, metaInfo: MetaInfo): F[Unit] =
          ref
            .update { map =>
              map.updated(infoHash, metaInfo)
            }
      }

    }
}
