/*
 * Copyright (C) 2018 Simer Plaha (@simerplaha)
 *
 * This file is a part of SwayDB.
 *
 * SwayDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * SwayDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with SwayDB. If not, see <https://www.gnu.org/licenses/>.
 */

package swaydb

import swaydb.types._

sealed trait Batch[K, +V]

object Batch {

  /**
    * Batch Put key & value for a [[SwayDBMap]]
    */
  case class Put[K, V](key: K, value: V) extends Batch[K, V]

  /**
    * Batch Put key & value for a [[SwayDBSet]]
    */
  case class Add[T](elem: T) extends Batch[T, Nothing]

  /**
    * Batch remove for [[SwayDBMap]] & [[SwayDBSet]]
    */
  case class Remove[K](key: K) extends Batch[K, Nothing]
}
