package com.github.lavrov.bencode

import cats._
import cats.data.ReaderT
import cats.implicits._
import scodec.Codec
import scodec.bits.ByteVector
import shapeless.Typeable

package object reader {

  type ER[R] = Either[BencodeFormatException, R]
  type BReader[A] = ReaderT[ER, Bencode, A]
  type BWriter[A] = ReaderT[ER, A, Bencode]
  type BDWriter[A] = ReaderT[ER, A, Bencode.Dictionary]

  case class BencodeFormat[A](read: BReader[A], write: BWriter[A]) {

    def upcast[B >: A](implicit ta: Typeable[A]): BencodeFormat[B] = reader.upcast[A, B](this)

    def or(that: BencodeFormat[A]): BencodeFormat[A] = reader.or(this, that)

    def and[B](that: BencodeFormat[B]): BencodeFormat[(A, B)] = reader.and(this, that)

    def consume[B](f: A => BencodeFormat[B], g: B => A): BencodeFormat[B] =
      reader.consume(this)(f, g)
  }

  object BencodeFormat {

    implicit val LongReader: BencodeFormat[Long] = BencodeFormat(
      ReaderT {
        case Bencode.Integer(l) => Right(l)
        case _ => Left(BencodeFormatException("Integer is expected"))
      },
      ReaderT { value =>
        Right(Bencode.Integer(value))
      }
    )

    implicit val StringReader: BencodeFormat[String] = BencodeFormat(
      ReaderT {
        case Bencode.String(v) => v.decodeAscii.left.map(BencodeFormatException("Unable to decode ascii", _))
        case other => Left(BencodeFormatException(s"String is expected, $other found"))
      },
      ReaderT { value =>
        Right(Bencode.String.apply(value))
      }
    )

    implicit val ByteVectorReader: BencodeFormat[ByteVector] = BencodeFormat(
      ReaderT {
        case Bencode.String(v) => Right(v)
        case _ => Left(BencodeFormatException("String is expected"))
      },
      ReaderT { bv =>
        Right(Bencode.String(bv))
      }
    )

    implicit def listReader[A: BencodeFormat]: BencodeFormat[List[A]] = {
      import cats.syntax.traverse._, cats.instances.list._
      val aReader: BencodeFormat[A] = implicitly
      BencodeFormat(
        ReaderT {
          case Bencode.List(values) =>
            values.traverse(aReader.read.run)
          case _ =>
            Left(BencodeFormatException("List is expected"))
        },
        ReaderT { values: List[A] =>
          values.traverse(aReader.write.run).map(Bencode.List)
        }
      )
    }

    implicit val RawValueReader: BencodeFormat[Bencode] = BencodeFormat(
      ReaderT {
        case bencode => Right(bencode)
      },
      ReaderT { value =>
        Right(value)
      }
    )

    implicit val BencodeFormatInvariant: Invariant[BencodeFormat] = new Invariant[BencodeFormat] {
      def imap[A, B](fa: BencodeFormat[A])(f: A => B)(g: B => A): BencodeFormat[B] = BencodeFormat(
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
                    case Bencode.Dictionary(values) => Right(values)
                    case other => Left(BencodeFormatException("Dictionary expected"))
                  }
                  bb <- fb.write(b).right.flatMap {
                    case Bencode.Dictionary(values) => Right(values)
                    case other => Left(BencodeFormatException("Dictionary expected"))
                  }
                } yield Bencode.Dictionary(ab ++ bb)
            }
          )
      }
  }

  def consume[A, B](
      format: BencodeFormat[A]
  )(f: A => BencodeFormat[B], g: B => A): BencodeFormat[B] = BencodeFormat(
    ReaderT { bv: Bencode =>
      format.read(bv).flatMap(f(_).read(bv))
    },
    ReaderT { b: B =>
      val a = g(b)
      val bFromat = f(a)
      for {
        aa <- format.write(a).flatMap {
          case Bencode.Dictionary(values) => Right(values)
          case other => Left(BencodeFormatException("Dictionary expected"))
        }
        bb <- bFromat.write(b).flatMap {
          case Bencode.Dictionary(values) => Right(values)
          case other => Left(BencodeFormatException("Dictionary expected"))
        }
      } yield Bencode.Dictionary(aa ++ bb)
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

  def or[A](x: BencodeFormat[A], y: BencodeFormat[A]): BencodeFormat[A] = BencodeFormat(
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
        case Bencode.Dictionary(values) =>
          values
            .get(name)
            .toRight(BencodeFormatException(s"Field $name not found. Available fields: ${values.keys}"))
            .flatMap(bReader.read.run)
        case _ =>
          Left(BencodeFormatException("Dictionary is expected"))
      },
      ReaderT { a: A =>
        bReader.write.run(a).map(bb => Bencode.Dictionary(Map(name -> bb)))
      }
    )

  def matchField[A: Eq](name: String, value: A)(
      implicit format: BencodeFormat[A]
  ): BencodeFormat[Unit] = {
    val df = field[A](name)
    BencodeFormat(
      df.read
        .flatMapF(a => Either.cond(a === value, (), BencodeFormatException(s"Expected field $name='$value' but found $a"))),
      df.write.contramap(_ => value)
    )
  }

  def optField[A](name: String)(implicit bReader: BencodeFormat[A]): BencodeFormat[Option[A]] =
    BencodeFormat(
      ReaderT {
        case Bencode.Dictionary(values) =>
          values.get(name).map(bReader.read.run).map(_.map(Some(_))).getOrElse(Right(None))
        case _ =>
          Left(BencodeFormatException("Dictionary is expected"))
      },
      ReaderT {
        case Some(value) =>
          bReader.write.run(value).map(bb => Bencode.Dictionary(Map(name -> bb)))
        case _ =>
          Right(Bencode.Dictionary(Map.empty))
      }
    )

  def encodedString[A](codec: Codec[A]): BencodeFormat[A] = BencodeFormat(
    BencodeFormat.ByteVectorReader.read.flatMapF { bv =>
      codec.decodeValue(bv.toBitVector).toEither.left.map(err => BencodeFormatException(err.messageWithContext))
    },
    ReaderT { v: A =>
      codec.encode(v).toEither.bimap(err => BencodeFormatException(err.messageWithContext), bv => Bencode.String(bv.toByteVector))
    }
  )

}

case class BencodeFormatException(message: String, cause: Throwable = null) extends Exception(message, cause)
