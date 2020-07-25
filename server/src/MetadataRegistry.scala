import cats.implicits._
import cats.effect.Sync
import cats.effect.concurrent.Ref
import com.github.lavrov.bittorrent.MetaInfo
import com.github.lavrov.bittorrent.app.domain.InfoHash

import scala.collection.immutable.ListMap

trait MetadataRegistry[F[_]] {

  def recent: F[Iterable[(InfoHash, MetaInfo)]]

  def get(infoHash: InfoHash): F[Option[MetaInfo]]

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

        def get(infoHash: InfoHash): F[Option[MetaInfo]] =
          ref.get.map(_.get(infoHash))

        def put(infoHash: InfoHash, metaInfo: MetaInfo): F[Unit] =
          ref
            .update { map =>
              map.updated(infoHash, metaInfo)
            }
      }

    }
}
