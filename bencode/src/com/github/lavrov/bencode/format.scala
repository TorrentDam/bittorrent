package com.github.lavrov.bencode

import cats._
import cats.data.ReaderT
import cats.implicits._
import scodec.Codec
import scodec.bits.ByteVector
import shapeless.Typeable

package object format {

  type Result[A] = Either[BencodeFormatException, A]
  type BencodeReader[A] = ReaderT[Result, Bencode, A]
  def BencodeReader[A](f: Bencode => Result[A]): BencodeReader[A] = ReaderT(f)
  type BencodeWriter[A] = ReaderT[Result, A, Bencode]
  def BencodeWriter[A](f: A => Result[Bencode]): BencodeWriter[A] = ReaderT(f)
  type BencodeDictionaryWriter[A] = ReaderT[Result, A, Bencode.BDictionary]
  case class BencodeFormat[A](read: BencodeReader[A], write: BencodeWriter[A])

  implicit class BencodeFormatSyntax[A](val self: BencodeFormat[A]) extends AnyVal {

    def upcast[B >: A](implicit ta: Typeable[A]): BencodeFormat[B] = format.upcast[A, B](self)

    def or(that: BencodeFormat[A]): BencodeFormat[A] = format.or(self, that)

    def and[B](that: BencodeFormat[B]): BencodeFormat[(A, B)] = format.and(self, that)

    def consume[B](f: A => BencodeFormat[B], g: B => A): BencodeFormat[B] =
      format.consume(self)(f, g)
  }

  object BencodeFormat {

    implicit val LongReader: BencodeFormat[Long] = BencodeFormat(
      ReaderT {
        case Bencode.BInteger(l) => Right(l)
        case _ => Left(BencodeFormatException("Integer is expected"))
      },
      ReaderT { value =>
        Right(Bencode.BInteger(value))
      }
    )

    implicit val StringReader: BencodeFormat[String] = BencodeFormat(
      ReaderT {
        case Bencode.BString(v) =>
          v.decodeUtf8.left.map(BencodeFormatException("Unable to decode UTF8", _))
        case other => Left(BencodeFormatException(s"String is expected, $other found"))
      },
      ReaderT { value =>
        Right(Bencode.BString.apply(value))
      }
    )

    implicit val ByteVectorReader: BencodeFormat[ByteVector] = BencodeFormat(
      ReaderT {
        case Bencode.BString(v) => Right(v)
        case _ => Left(BencodeFormatException("String is expected"))
      },
      ReaderT { bv =>
        Right(Bencode.BString(bv))
      }
    )

    implicit def listReader[A: BencodeFormat]: BencodeFormat[List[A]] = {
      import cats.syntax.traverse._, cats.instances.list._
      val aReader: BencodeFormat[A] = implicitly
      BencodeFormat(
        ReaderT {
          case Bencode.BList(values) =>
            values.traverse(aReader.read.run)
          case _ =>
            Left(BencodeFormatException("List is expected"))
        },
        ReaderT { values: List[A] =>
          values.traverse(aReader.write.run).map(Bencode.BList)
        }
      )
    }

    implicit def mapReader[A: BencodeFormat]: BencodeFormat[Map[String, A]] = {
      import cats.syntax.traverse._, cats.instances.list._
      val aReader: BencodeFormat[A] = implicitly
      BencodeFormat(
        ReaderT {
          case Bencode.BDictionary(values) =>
            values.toList
              .traverse {
                case (key, value) =>
                  aReader.read.run(value).tupleLeft(key)
              }
              .map(_.toMap)
          case _ =>
            Left(BencodeFormatException("Dictionary is expected"))
        },
        ReaderT { values: Map[String, A] =>
          values.toList
            .traverse {
              case (key, value) => aReader.write.run(value).tupleLeft(key)
            }
            .map(v => Bencode.BDictionary(v: _*))
        }
      )
    }

    implicit val BencodeValueFormat: BencodeFormat[Bencode] = BencodeFormat(
      ReaderT { bencode =>
        Right(bencode)
      },
      ReaderT { value =>
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
            ReaderT {
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

  def consume[A, B](
    format: BencodeFormat[A]
  )(f: A => BencodeFormat[B], g: B => A): BencodeFormat[B] =
    BencodeFormat(
      ReaderT { bv: Bencode =>
        format.read(bv).flatMap(f(_).read(bv))
      },
      ReaderT { b: B =>
        val a = g(b)
        val bFromat = f(a)
        for {
          aa <- format.write(a).flatMap {
            case Bencode.BDictionary(values) => Right(values)
            case other => Left(BencodeFormatException("Dictionary expected"))
          }
          bb <- bFromat.write(b).flatMap {
            case Bencode.BDictionary(values) => Right(values)
            case other => Left(BencodeFormatException("Dictionary expected"))
          }
        } yield Bencode.BDictionary(aa ++ bb)
      }
    )

  def upcast[A, B >: A](x: BencodeFormat[A])(implicit ta: Typeable[A]): BencodeFormat[B] =
    BencodeFormat(
      x.read.widen[B],
      ReaderT { b: B =>
        ta.cast(b) match {
          case Some(a) => x.write(a)
          case None => Left(BencodeFormatException(s"not a value of type ${ta.describe}"))
        }
      }
    )

  def and[A, B](x: BencodeFormat[A], y: BencodeFormat[B]): BencodeFormat[(A, B)] = (x, y).tupled

  def or[A](x: BencodeFormat[A], y: BencodeFormat[A]): BencodeFormat[A] =
    BencodeFormat(
      ReaderT { bencodeValue: Bencode =>
        x.read(bencodeValue).left.flatMap(_ => y.read(bencodeValue))
      },
      ReaderT { a: A =>
        x.write(a).left.flatMap(_ => y.write(a))
      }
    )

  def field[A](name: String)(implicit bReader: BencodeFormat[A]): BencodeFormat[A] =
    BencodeFormat(
      ReaderT {
        case Bencode.BDictionary(values) =>
          values
            .get(name)
            .toRight(
              BencodeFormatException(s"Faield $name not found. Available fields: ${values.keys}")
            )
            .flatMap(bReader.read.run)
            .leftMap { cause =>
              BencodeFormatException(s"Faield while reading '$name'", cause)
            }
        case _ =>
          Left(BencodeFormatException("Dictionary is expected"))
      },
      ReaderT { a: A =>
        bReader.write.run(a).map(bb => Bencode.BDictionary(Map(name -> bb)))
      }
    )

  def fieldOptional[A](name: String)(implicit bReader: BencodeFormat[A]): BencodeFormat[Option[A]] =
    BencodeFormat(
      ReaderT {
        case Bencode.BDictionary(values) =>
          values.get(name).map(bReader.read.run).map(_.map(Some(_))).getOrElse(Right(None))
        case _ =>
          Left(BencodeFormatException("Dictionary is expected"))
      },
      ReaderT {
        case Some(value) =>
          bReader.write.run(value).map(bb => Bencode.BDictionary(Map(name -> bb)))
        case _ =>
          Right(Bencode.BDictionary.Empty)
      }
    )

  def encodedString[A](codec: Codec[A]): BencodeFormat[A] =
    BencodeFormat(
      BencodeFormat.ByteVectorReader.read.flatMapF { bv =>
        codec
          .decodeValue(bv.toBitVector)
          .toEither
          .left
          .map(err => BencodeFormatException(err.messageWithContext))
      },
      ReaderT { v: A =>
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
