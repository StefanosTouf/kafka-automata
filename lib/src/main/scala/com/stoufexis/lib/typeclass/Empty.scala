package com.stoufexis.lib.typeclass

import cats.kernel.Monoid

trait Empty[A] {
  val empty: A
}

object Empty {
  implicit def emptyFromMonoid[A: Monoid]: Empty[A] =
    new Empty[A] {
      override val empty: A = Monoid[A].empty
    }
}
