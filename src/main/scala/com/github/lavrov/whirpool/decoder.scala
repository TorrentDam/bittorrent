package com.github.lavrov.whirpool

import cats.Monad
import cats.data.ReaderT
import cats.instances.either.catsStdInstancesForEither
import com.github.lavrov.whirpool.bencode.Bencode
import io.estatico.newtype.macros.newtype

package object decoder {

  type ER[R] = Either[String, R]
  type RTR[A] = ReaderT[ER, Bencode, A]

  @newtype case class BencodeDecoder[A](value: RTR[A]) {
    def decode(bencode: Bencode) = value.run(bencode)
  }

  object BencodeDecoder {

    def field[A](name: String)(implicit bDecoder: BencodeDecoder[A]): BencodeDecoder[A] = BencodeDecoder {
      ReaderT {
        case Bencode.Dictionary(values) =>
          values.get(name).toRight(s"Field $name not found").flatMap(bDecoder.value.run)
        case _ =>
          Left("Dictionary is expected")
      }
    }

    implicit val LongDecoder: BencodeDecoder[Long] = BencodeDecoder(
      ReaderT {
        case Bencode.Integer(l) => Right(l)
        case _ => Left("Integer is expected")
      }
    )

    implicit val StringDecoder: BencodeDecoder[String] = BencodeDecoder(
      ReaderT {
        case Bencode.String(v) => Right(v)
        case _ => Left("Integer is expected")
      }
    )

    implicit val MonadInstance: Monad[BencodeDecoder] = derivingK
  }
}

