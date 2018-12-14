package com.github.lavrov.bencode

import cats._
import cats.data.ReaderT
import cats.implicits._
import scodec.bits.ByteVector
import shapeless.Typeable

package object reader {

  type ER[R] = Either[String, R]
  type BReader[A] = ReaderT[ER, Bencode, A]
  type BWriter[A] = ReaderT[ER, A, Bencode]
  type BDWriter[A] = ReaderT[ER, A, Bencode.Dictionary]

  case class BencodeFormat[A](read: BReader[A], write: BWriter[A]) {

    def upcast[B >: A](implicit ta: Typeable[A]): BencodeFormat[B] = reader.upcast[A, B](this)

    def or(that: BencodeFormat[A]): BencodeFormat[A] = reader.or(this, that)
  }

  object BencodeFormat {

    implicit val LongReader: BencodeFormat[Long] = BencodeFormat(
      ReaderT {
        case Bencode.Integer(l) => Right(l)
        case _ => Left("Integer is expected")
      },
      ReaderT { value =>
        Right(Bencode.Integer(value))
      }
    )

    implicit val StringReader: BencodeFormat[String] = BencodeFormat(
      ReaderT {
        case Bencode.String(v) => v.decodeAscii.left.map(_.getMessage)
        case other => Left(s"String is expected, $other found")
      },
      ReaderT { value =>
        Right(Bencode.String.apply(value))
      }
    )

    implicit val ByteVectorReader: BencodeFormat[ByteVector] = BencodeFormat(
      ReaderT {
        case Bencode.String(v) => Right(v)
        case _ => Left("String is expected")
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
            Left("List is expected")
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
      ReaderT {
        value => Right(value)
      }
    )

    implicit val BencodeInvariant: Invariant[BencodeFormat] = new Invariant[BencodeFormat] {
      def imap[A, B](fa: BencodeFormat[A])(f: A => B)(g: B => A): BencodeFormat[B] = BencodeFormat(
        fa.read.map(f),
        fa.write.contramap(g)
      )
    }
  }

  case class DictionaryFormat[A](read: BReader[A], write: BDWriter[A]) {

    def generalize = BencodeFormat(read, write.map(d => d))

    def and[B](that: DictionaryFormat[B]): DictionaryFormat[(A, B)] = reader.and(this, that)
  }

  object DictionaryFormat {

    implicit val DictionaryFormatInvariant: Invariant[DictionaryFormat] = new Invariant[DictionaryFormat] {
      def imap[A, B](fa: DictionaryFormat[A])(f: A => B)(g: B => A): DictionaryFormat[B] = DictionaryFormat(
        fa.read.map(f),
        fa.write.contramap(g)
      )
    }

    implicit val DictionaryFormatSemigroupal: Semigroupal[DictionaryFormat] = new Semigroupal[DictionaryFormat] {
      def product[A, B](fa: DictionaryFormat[A], fb: DictionaryFormat[B]): DictionaryFormat[(A, B)] =
        DictionaryFormat[(A, B)](
          (fa.read, fb.read).tupled,
          ReaderT {
            case (a, b) =>
              for {
                ab <- fa.write(a)
                bb <- fb.write(b)
              }
                yield
                  Bencode.Dictionary(ab.values ++ bb.values)
          }
        )
    }

  }


  def upcast[A, B >: A](x: BencodeFormat[A])(implicit ta: Typeable[A]): BencodeFormat[B] = BencodeFormat(
    x.read.widen[B],
    ReaderT { b: B =>
      ta.cast(b) match {
        case Some(a) => x.write(a)
        case None => Left(s"not a value of type ${ta.describe}")
      }
    }
  )

  def and[A, B](x: DictionaryFormat[A], y: DictionaryFormat[B]): DictionaryFormat[(A, B)] = (x, y).tupled

  def or[A](x: BencodeFormat[A], y: BencodeFormat[A]): BencodeFormat[A] = BencodeFormat(
    ReaderT { bencodeValue: Bencode =>
      x.read(bencodeValue).left.flatMap(_ => y.read(bencodeValue))
    },
    ReaderT { a: A =>
      x.write(a).left.flatMap(_ => y.write(a))
    }
  )

  def field[A](name: String)(implicit bReader: BencodeFormat[A]): DictionaryFormat[A] =
    DictionaryFormat(
      ReaderT {
        case Bencode.Dictionary(values) =>
          values
            .get(name)
            .toRight(s"Field $name not found. Available fields: ${values.keys}")
            .flatMap(bReader.read.run)
        case _ =>
          Left("Dictionary is expected")
      },
      ReaderT { a: A =>
        bReader.write.run(a).map(bb => Bencode.Dictionary(Map(name -> bb)))
      }
    )

  def optField[A](name: String)(implicit bReader: BencodeFormat[A]): DictionaryFormat[Option[A]] =
    DictionaryFormat(
      ReaderT {
        case Bencode.Dictionary(values) =>
          values.get(name).map(bReader.read.run).map(_.map(Some(_))).getOrElse(Right(None))
        case _ =>
          Left("Dictionary is expected")
      },
      ReaderT {
        case Some(value) =>
          bReader.write.run(value).map(bb => Bencode.Dictionary(Map(name -> bb)))
        case _ =>
          Right(Bencode.Dictionary(Map.empty))
      }
    )

}
