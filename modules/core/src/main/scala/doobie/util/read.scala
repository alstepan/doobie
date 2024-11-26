// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.util

import cats.Applicative
import doobie.ResultSetIO
import doobie.enumerated.Nullability
import doobie.enumerated.Nullability.{NoNulls, NullabilityKnown}
import doobie.free.resultset as IFRS

import java.sql.ResultSet
import scala.annotation.implicitNotFound

@implicitNotFound("""
Cannot find or construct a Read instance for type:

  ${A}

This can happen for a few reasons, but the most common case is that a data
member somewhere within this type doesn't have a Get instance in scope. Here are
some debugging hints:

- For auto derivation ensure `doobie.implicits._` or `doobie.generic.auto._` is
  being imported
- For Option types, ensure that a Read instance is in scope for the non-Option
  version.
- For types you expect to map to a single column ensure that a Get instance is
  in scope.
- For case classes, shapeless HLists/records ensure that each element
  has a Read instance in scope.
- Lather, rinse, repeat, recursively until you find the problematic bit.

You can check that an instance exists for Read in the REPL or in your code:

  scala> Read[Foo]

and similarly with Get:

  scala> Get[Foo]

And find the missing instance and construct it as needed. Refer to Chapter 12
of the book of doobie for more information.
""")
sealed trait Read[A] {
  def unsafeGet(rs: ResultSet, startIdx: Int): A
  def gets: List[(Get[?], NullabilityKnown)]
  def toOpt: Read[Option[A]]
  def length: Int

  final def get(n: Int): ResultSetIO[A] =
    IFRS.raw(unsafeGet(_, n))

  final def map[B](f: A => B): Read[B] = new Read.Transform[B, A](this, f)

  final def ap[B](ff: Read[A => B]): Read[B] = {
    new Read.Composite[B, A => B, A](ff, this, (f, a) => f(a))
  }
}

object Read extends LowerPriorityRead {

  def apply[A](implicit ev: Read[A]): Read[A] = ev

  def derived[A](implicit
      @implicitNotFound(
        "Cannot derive Read instance. Please check that each field in the case class has a Read instance or can derive one")
      ev: Derived[MkRead[A]]
  ): Read[A] = ev.instance.underlying

  trait Auto extends MkReadInstances

  implicit val ReadApply: Applicative[Read] =
    new Applicative[Read] {
      def ap[A, B](ff: Read[A => B])(fa: Read[A]): Read[B] = fa.ap(ff)
      def pure[A](x: A): Read[A] = Read.unit.map(_ => x)
      override def map[A, B](fa: Read[A])(f: A => B): Read[B] = fa.map(f)
    }

  implicit val unit: Read[Unit] = new Read[Unit] {
    override def unsafeGet(rs: ResultSet, startIdx: Int): Unit = {
      () // Does not read anything from ResultSet
    }
    override def gets: List[(Get[?], NullabilityKnown)] = List.empty
    override def toOpt: Read[Option[Unit]] = optionUnit
    override def length: Int = 0
  }

  implicit val optionUnit: Read[Option[Unit]] = unit.map(_ => Some(()))

  implicit def fromReadOption[A](implicit read: Read[A]): Read[Option[A]] = read.toOpt

  /** Simple instance wrapping a Get. i.e. single column non-null value */
  class Single[A](get: Get[A]) extends Read[A] {
    def unsafeGet(rs: ResultSet, startIdx: Int): A =
      get.unsafeGetNonNullable(rs, startIdx)

    override def toOpt: Read[Option[A]] = new SingleOpt(get)

    override def gets: List[(Get[?], NullabilityKnown)] = List(get -> NoNulls)

    override val length: Int = 1

  }

  /** Simple instance wrapping a Get. i.e. single column nullable value */
  class SingleOpt[A](get: Get[A]) extends Read[Option[A]] {
    def unsafeGet(rs: ResultSet, startIdx: Int): Option[A] =
      get.unsafeGetNullable(rs, startIdx)

    override def toOpt: Read[Option[Option[A]]] = new Transform[Option[Option[A]], Option[A]](this, a => Some(a))
    override def gets: List[(Get[?], NullabilityKnown)] = List(get -> Nullability.Nullable)

    override val length: Int = 1
  }

  class Transform[A, From](underlyingRead: Read[From], f: From => A) extends Read[A] {
    override def unsafeGet(rs: ResultSet, startIdx: Int): A = f(underlyingRead.unsafeGet(rs, startIdx))
    override def gets: List[(Get[?], NullabilityKnown)] = underlyingRead.gets
    override def toOpt: Read[Option[A]] =
      new Transform[Option[A], Option[From]](underlyingRead.toOpt, opt => opt.map(f))
    override lazy val length: Int = underlyingRead.length
  }

  /** A Read instance consists of multiple underlying Read instances */
  class Composite[A, S0, S1](read0: Read[S0], read1: Read[S1], f: (S0, S1) => A) extends Read[A] {
    override def unsafeGet(rs: ResultSet, startIdx: Int): A = {
      val r0 = read0.unsafeGet(rs, startIdx)
      val r1 = read1.unsafeGet(rs, startIdx + read0.length)
      f(r0, r1)
    }

    override lazy val gets: List[(Get[?], NullabilityKnown)] =
      read0.gets ++ read1.gets

    override def toOpt: Read[Option[A]] = {
      val readOpt0 = read0.toOpt
      val readOpt1 = read1.toOpt
      new Composite[Option[A], Option[S0], Option[S1]](
        readOpt0,
        readOpt1,
        {
          case (Some(s0), Some(s1)) => Some(f(s0, s1))
          case _                    => None
        })

    }
    override lazy val length: Int = read0.length + read1.length
  }

}

trait LowerPriorityRead extends ReadPlatform {

  implicit def fromGet[A](implicit get: Get[A]): Read[A] = new Read.Single(get)

  implicit def fromGetOption[A](implicit get: Get[A]): Read[Option[A]] = new Read.SingleOpt(get)

}

trait LowestPriorityRead {
  implicit def fromDerived[A](implicit ev: Derived[Read[A]]): Read[A] = ev.instance
}

final class MkRead[A](val underlying: Read[A]) extends Read[A] {
  override def unsafeGet(rs: ResultSet, startIdx: Int): A = underlying.unsafeGet(rs, startIdx)
  override def gets: List[(Get[?], NullabilityKnown)] = underlying.gets
  override def toOpt: Read[Option[A]] = underlying.toOpt
  override def length: Int = underlying.length
}

object MkRead extends MkReadInstances

trait MkReadInstances extends MkReadPlatform
