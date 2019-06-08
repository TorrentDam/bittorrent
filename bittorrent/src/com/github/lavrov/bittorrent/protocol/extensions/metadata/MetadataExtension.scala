package com.github.lavrov.bittorrent.protocol.extensions.metadata

trait MetadataExtension[F[_]] {
  def get(piece: Long): F[Unit]
}

object MetadataExtension {
  def make(messageId: Long) = ???
}
