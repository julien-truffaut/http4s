package org.http4s

import org.http4s.Header.`Accept-Charset`

import scala.collection.JavaConverters._
import java.nio.charset.{Charset => NioCharset}
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Arbitrary._
import org.scalacheck.Gen._

import scalaz.NonEmptyList
import scalaz.scalacheck.ScalazArbitrary._

trait TestInstances {
  implicit class ParseResultSyntax[A](self: ParseResult[A]) {
    def yolo: A = self.valueOr(e => sys.error(e.toString))
  }

  val tchars: Gen[Char] = Gen.oneOf {
    Seq('!', '#', '$', '%', '&', '\'', '*', '+', '-', '.', '^', '_', '`', '|', '~') ++
      ('0' to '9') ++ ('A' to 'Z') ++ ('a' to 'z')
  }
  val tokens: Gen[String] = nonEmptyListOf(tchars).map(_.mkString)

  val standardMethods: Gen[Method] = Gen.oneOf(Method.registered.toSeq)
  implicit val arbitraryMethod: Arbitrary[Method] = Arbitrary(frequency(
    10 -> standardMethods,
    1 -> tokens.map(Method.fromString(_).yolo)
  ))

  val validStatusCodes = Gen.choose(100, 599)
  val standardStatuses = Gen.oneOf(Status.registered.toSeq)
  val customStatuses = for {
    code <- validStatusCodes
    reason <- arbString.arbitrary
  } yield Status.fromIntAndReason(code, reason).yolo
  implicit val arbitraryStatuses: Arbitrary[Status] = Arbitrary(frequency(
    10 -> standardStatuses,
    1 -> customStatuses
  ))


  implicit val arbitraryHttpVersion: Arbitrary[HttpVersion] =
    Arbitrary { for {
      major <- choose(0, 9)
      minor <- choose(0, 9)
    } yield HttpVersion.fromVersion(major, minor).yolo }

  implicit val aribtraryNioCharset: Arbitrary[NioCharset] =
    Arbitrary(oneOf(NioCharset.availableCharsets.values.asScala.toSeq))

  implicit val arbitraryCharset: Arbitrary[Charset] =
    Arbitrary { arbitrary[NioCharset].map(Charset.fromNioCharset) }

  implicit val qValues: Arbitrary[QValue] =
    Arbitrary { Gen.oneOf(const(0), const(1000), choose(0, 1000)).map(QValue.fromThousandths(_).yolo) }

  implicit val arbitraryCharsetRange: Arbitrary[CharsetRange] =
    Arbitrary { frequency((10, arbitrary[CharsetRange.Atom]), (1, arbitrary[CharsetRange.`*`])) }
  implicit val arbitraryCharsetAtomRange: Arbitrary[CharsetRange.Atom] =
    Arbitrary { for {
      charset <- arbitrary[Charset]
      q <- arbitrary[QValue]
    } yield charset.withQuality(q) }
  implicit val arbitraryCharsetSplatRange: Arbitrary[CharsetRange.`*`] =
    Arbitrary { arbitrary[QValue].map(CharsetRange.`*`.withQValue(_)) }

  implicit val arbitraryAcceptCharset: Arbitrary[`Accept-Charset`] =
    Arbitrary { arbitrary[NonEmptyList[CharsetRange.`*`]].map(`Accept-Charset`(_)) }
}


