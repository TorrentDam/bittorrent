package com.github.lavrov.bencode

import cats.{Monad, SemigroupK}
import cats.data.ReaderT
import cats.instances.either.{catsStdInstancesForEither, catsStdSemigroupKForEither}
import io.estatico.newtype.macros.newtype
import scodec.bits.ByteVector

package object reader {

  type ER[R] = Either[String, R]
  type RTR[A] = ReaderT[ER, Bencode, A]

  @newtype case class BencodeReader[A](value: RTR[A]) {
    def read(bencode: Bencode): ER[A] = value.run(bencode)

    def and[B](that: BencodeReader[B]): BencodeReader[(A, B)] = BencodeReader.and(this, that)

    def or(that: BencodeReader[A]): BencodeReader[A] = BencodeReader.or(this, that)
  }

  object BencodeReader {

    def field[A](name: String)(implicit bReader: BencodeReader[A]): BencodeReader[A] = BencodeReader {
      ReaderT {
        case Bencode.Dictionary(values) =>
          values.get(name).toRight(s"Field $name not found. Available fields: ${values.keys}")
            .flatMap(bReader.value.run)
        case _ =>
          Left("Dictionary is expected")
      }
    }

    def optField[A](name: String)(implicit bReader: BencodeReader[A]): BencodeReader[Option[A]] = BencodeReader {
      ReaderT {
        case Bencode.Dictionary(values) =>
          values.get(name).map(bReader.value.run).map(_.map(Some(_))).getOrElse(Right(None))
        case _ =>
          Left("Dictionary is expected")
      }
    }

    implicit val LongReader: BencodeReader[Long] = BencodeReader(
      ReaderT {
        case Bencode.Integer(l) => Right(l)
        case _ => Left("Integer is expected")
      }
    )

    implicit val StringReader: BencodeReader[String] = BencodeReader(
      ReaderT {
        case Bencode.String(v) => v.decodeAscii.left.map(_.getMessage)
        case other => Left(s"String is expected, $other found")
      }
    )

    implicit val ByteVectorReader: BencodeReader[ByteVector] = BencodeReader(
      ReaderT {
        case Bencode.String(v) => Right(v)
        case _ => Left("String is expected")
      }
    )

    implicit def listReader[A: BencodeReader]: BencodeReader[List[A]] = BencodeReader {
      import cats.syntax.traverse._, cats.instances.list._
      val aReader: BencodeReader[A] = implicitly
      ReaderT {
        case Bencode.List(values) =>
          values.traverse(aReader.read)
        case _ =>
          Left("List is expected")
      }
    }

    implicit val RawValueReader: BencodeReader[Bencode] = BencodeReader(
      ReaderT {
        case bencode => Right(bencode)
      }
    )

    implicit val MonadInstance: Monad[BencodeReader] = derivingK
    private val SemigroupKInstance: SemigroupK[BencodeReader] = derivingK

    def and[A, B](x: BencodeReader[A], y: BencodeReader[B]): BencodeReader[(A, B)] = MonadInstance.tuple2(x, y)

    def or[A](x: BencodeReader[A], y: BencodeReader[A]): BencodeReader[A] = SemigroupKInstance.combineK(x, y)
  }
}
