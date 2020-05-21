package com.github.lavrov.bencode

import cats._
import cats.data.ReaderT
import cats.implicits._
import scodec.Codec
import scodec.bits.ByteVector
import shapeless.Typeable

package object format {

  type Result[+A] = Either[BencodeFormatException, A]

  type BencodeReader[A] = ReaderT[Result, Bencode, A]
  def BencodeReader[A](f: Bencode => Result[A]): BencodeReader[A] = ReaderT(f)

  type BencodeWriter[A] = ReaderT[Result, A, Bencode]
  def BencodeWriter[A](f: A => Result[Bencode]): BencodeWriter[A] = ReaderT(f)

  case class BencodeFormat[A](read: BencodeReader[A], write: BencodeWriter[A])

  object BencodeFormat {

    implicit class Ops[A](val self: BencodeFormat[A]) extends AnyVal {

      def upcast[B >: A](implicit ta: Typeable[A]): BencodeFormat[B] =
        BencodeFormat(
          self.read.widen[B],
          BencodeWriter { b: B =>
            ta.cast(b) match {
              case Some(a) => self.write(a)
              case None => Left(BencodeFormatException(s"not a value of type ${ta.describe}"))
            }
          }
        )

      def choose[B](f: A => BencodeFormat[B], g: B => A): BencodeFormat[B] =
        BencodeFormat(
          BencodeReader { bv: Bencode =>
            self.read(bv).flatMap { a =>
              val format = f(a)
              format.read(bv)
            }
          },
          BencodeWriter { b: B =>
            val a = g(b)
            val format = f(a)
            for {
              aa <- self.write(a).flatMap {
                case Bencode.BDictionary(values) => Right(values)
                case other =>
                  Left(BencodeFormatException(s"Dictionary expected but got ${other.getClass.getSimpleName}"))
              }
              bb <- format.write(b.asInstanceOf).flatMap {
                case Bencode.BDictionary(values) => Right(values)
                case other =>
                  Left(BencodeFormatException(s"Dictionary expected but got ${other.getClass.getSimpleName}"))
              }
            } yield Bencode.BDictionary(aa ++ bb)
          }
        )
    }

    implicit val LongFormat: BencodeFormat[Long] = BencodeFormat(
      BencodeReader {
        case Bencode.BInteger(l) => Right(l)
        case _ => Left(BencodeFormatException("Integer is expected"))
      },
      BencodeWriter { value =>
        Right(Bencode.BInteger(value))
      }
    )

    implicit val StringFormat: BencodeFormat[String] = BencodeFormat(
      BencodeReader {
        case Bencode.BString(v) =>
          v.decodeUtf8.left.map(BencodeFormatException("Unable to decode UTF8", _))
        case other => Left(BencodeFormatException(s"String is expected, $other found"))
      },
      BencodeWriter { value =>
        Right(Bencode.BString.apply(value))
      }
    )

    implicit val ByteVectorFormat: BencodeFormat[ByteVector] = BencodeFormat(
      BencodeReader {
        case Bencode.BString(v) => Right(v)
        case _ => Left(BencodeFormatException("String is expected"))
      },
      BencodeWriter { bv =>
        Right(Bencode.BString(bv))
      }
    )

    implicit def listFormat[A: BencodeFormat]: BencodeFormat[List[A]] = {
      import cats.syntax.traverse._, cats.instances.list._
      val aFormat: BencodeFormat[A] = implicitly
      BencodeFormat(
        BencodeReader {
          case Bencode.BList(values) =>
            values.traverse(aFormat.read.run)
          case _ =>
            Left(BencodeFormatException("List is expected"))
        },
        BencodeWriter { values: List[A] =>
          values.traverse(aFormat.write.run).map(Bencode.BList)
        }
      )
    }

    implicit val dictionaryFormat: BencodeFormat[Bencode.BDictionary] = {
      BencodeFormat(
        BencodeReader {
          case value @ Bencode.BDictionary(_) => Right(value)
          case _ => Left(BencodeFormatException("Dictionary is expected"))
        },
        BencodeWriter { value => Right(value) }
      )
    }

    implicit def mapFormat[A: BencodeFormat]: BencodeFormat[Map[String, A]] = {
      import cats.syntax.traverse._, cats.instances.list._
      val aFormat: BencodeFormat[A] = implicitly
      BencodeFormat(
        BencodeReader {
          case Bencode.BDictionary(values) =>
            values.toList
              .traverse {
                case (key, value) =>
                  aFormat.read.run(value).tupleLeft(key)
              }
              .map(_.toMap)
          case _ =>
            Left(BencodeFormatException("Dictionary is expected"))
        },
        BencodeWriter { values: Map[String, A] =>
          values.toList
            .traverse {
              case (key, value) => aFormat.write.run(value).tupleLeft(key)
            }
            .map(v => Bencode.BDictionary(v: _*))
        }
      )
    }

    implicit val BencodeValueFormat: BencodeFormat[Bencode] = BencodeFormat(
      BencodeReader { bencode =>
        Right(bencode)
      },
      BencodeWriter { value =>
        Right(value)
      }
    )

    implicit val BencodeFormatInvariant: Invariant[BencodeFormat] = new Invariant[BencodeFormat] {
      def imap[A, B](fa: BencodeFormat[A])(f: A => B)(g: B => A): BencodeFormat[B] =
        BencodeFormat(
          fa.read.map(f),
          fa.write.contramap(g)
        )
    }

    implicit val BencodeFormatSemigroupal: Semigroupal[BencodeFormat] =
      new Semigroupal[BencodeFormat] {
        def product[A, B](fa: BencodeFormat[A], fb: BencodeFormat[B]): BencodeFormat[(A, B)] =
          BencodeFormat[(A, B)](
            (fa.read, fb.read).tupled,
            BencodeWriter {
              case (a, b) =>
                for {
                  ab <- fa.write(a).right.flatMap {
                    case Bencode.BDictionary(values) => Right(values)
                    case other => Left(BencodeFormatException("Dictionary expected"))
                  }
                  bb <- fb.write(b).right.flatMap {
                    case Bencode.BDictionary(values) => Right(values)
                    case other => Left(BencodeFormatException("Dictionary expected"))
                  }
                } yield Bencode.BDictionary(ab ++ bb)
            }
          )
      }
  }

  def field[A](name: String)(implicit bFormat: BencodeFormat[A]): BencodeFormat[A] =
    BencodeFormat(
      BencodeReader {
        case Bencode.BDictionary(values) =>
          values
            .get(name)
            .toRight(
              BencodeFormatException(s"Faield $name not found. Available fields: ${values.keys}")
            )
            .flatMap(bFormat.read.run)
            .leftMap { cause =>
              BencodeFormatException(s"Faield while reading '$name'", cause)
            }
        case _ =>
          Left(BencodeFormatException("Dictionary is expected"))
      },
      BencodeWriter { a: A =>
        bFormat.write.run(a).map(bb => Bencode.BDictionary(Map(name -> bb)))
      }
    )

  def fieldOptional[A](name: String)(implicit bFormat: BencodeFormat[A]): BencodeFormat[Option[A]] =
    BencodeFormat(
      BencodeReader {
        case Bencode.BDictionary(values) =>
          values.get(name).map(bFormat.read.run).map(_.map(Some(_))).getOrElse(Right(None))
        case _ =>
          Left(BencodeFormatException("Dictionary is expected"))
      },
      BencodeWriter {
        case Some(value) =>
          bFormat.write.run(value).map(bb => Bencode.BDictionary(Map(name -> bb)))
        case _ =>
          Right(Bencode.BDictionary.Empty)
      }
    )

  def encodedString[A](codec: Codec[A]): BencodeFormat[A] =
    BencodeFormat(
      BencodeFormat.ByteVectorFormat.read.flatMapF { bv =>
        codec
          .decodeValue(bv.toBitVector)
          .toEither
          .left
          .map(err => BencodeFormatException(err.messageWithContext))
      },
      BencodeWriter { v: A =>
        codec
          .encode(v)
          .toEither
          .bimap(
            err => BencodeFormatException(err.messageWithContext),
            bv => Bencode.BString(bv.toByteVector)
          )
      }
    )

}

case class BencodeFormatException(message: String, cause: Throwable = null) extends Exception(message, cause)
