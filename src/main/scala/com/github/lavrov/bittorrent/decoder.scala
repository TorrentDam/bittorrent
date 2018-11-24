package com.github.lavrov.bittorrent

import cats.{Monad, SemigroupK}
import cats.data.ReaderT
import cats.instances.either.{catsStdInstancesForEither, catsStdSemigroupKForEither}
import com.github.lavrov.bittorrent.bencode.Bencode
import io.estatico.newtype.macros.newtype
import scodec.bits.ByteVector

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

    def optField[A](name: String)(implicit bDecoder: BencodeDecoder[A]): BencodeDecoder[Option[A]] = BencodeDecoder {
      ReaderT {
        case Bencode.Dictionary(values) =>
          values.get(name).map(bDecoder.value.run).map(_.map(Some(_))).getOrElse(Right(None))
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
        case Bencode.String(v) => v.decodeAscii.left.map(_.getMessage)
        case _ => Left("String is expected")
      }
    )

    implicit val ByteVectorDecoder: BencodeDecoder[ByteVector] = BencodeDecoder(
      ReaderT {
        case Bencode.String(v) => Right(v)
        case _ => Left("String is expected")
      }
    )

    implicit def listDecoder[A: BencodeDecoder]: BencodeDecoder[List[A]] = BencodeDecoder {
      import cats.syntax.traverse._, cats.instances.list._
      val aDecoder: BencodeDecoder[A] = implicitly
      ReaderT {
        case Bencode.List(values) =>
          values.traverse(aDecoder.decode)
        case _ =>
          Left("List is expected")
      }
    }

    implicit val MonadInstance: Monad[BencodeDecoder] = derivingK
    implicit val SemigroupKInstance: SemigroupK[BencodeDecoder] = derivingK
  }
}

