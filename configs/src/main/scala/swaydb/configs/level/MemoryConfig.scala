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

package swaydb.configs.level

import java.nio.file.Path

import swaydb.data.accelerate.{Accelerator, Level0Meter}
import swaydb.data.compaction.Throttle
import swaydb.data.config._

import scala.concurrent.duration._

object MemoryConfig {

  /**
    * Default configuration for 2 leveled Memory database.
    */
  def apply(mapSize: Int,
            segmentSize: Int,
            bloomFilterFalsePositiveRate: Double,
            acceleration: Level0Meter => Accelerator): SwayDBMemoryConfig =
    ConfigWizard
      .addMemoryLevel0(
        mapSize = mapSize,
        acceleration = acceleration
      )
      .addMemoryLevel1(
        segmentSize = segmentSize,
        pushForward = false,
        bloomFilterFalsePositiveRate = bloomFilterFalsePositiveRate,
        throttle = (_) => Throttle(Duration.Zero, 10)
      )

  /**
    * Default configuration for in-memory 3 leveled database that is persistent for the 3rd Level.
    */
  def apply(dir: Path,
            otherDirs: Seq[Dir],
            mapSize: Int,
            maxMemoryLevelSize: Int,
            maxSegmentsToPush: Int,
            memoryLevelSegmentSize: Int,
            persistentLevelSegmentSize: Int,
            persistentLevelAppendixFlushCheckpointSize: Int,
            cachePersistentLevelKeyValues: Boolean,
            mmapPersistentSegments: MMAP,
            mmapPersistentAppendix: Boolean,
            bloomFilterFalsePositiveRate: Double,
            acceleration: Level0Meter => Accelerator): SwayDBPersistentConfig =
    ConfigWizard
      .addMemoryLevel0(
        mapSize = mapSize,
        acceleration = acceleration
      )
      .addMemoryLevel1(
        segmentSize = memoryLevelSegmentSize,
        pushForward = false,
        bloomFilterFalsePositiveRate = bloomFilterFalsePositiveRate,
        throttle =
          levelMeter => {
            if (levelMeter.levelSize > maxMemoryLevelSize)
              Throttle(Duration.Zero, maxSegmentsToPush)
            else
              Throttle(Duration.Zero, 0)
          }
      )
      .addPersistentLevel(
        dir = dir,
        otherDirs = otherDirs,
        segmentSize = persistentLevelSegmentSize,
        mmapSegment = mmapPersistentSegments,
        mmapAppendix = mmapPersistentAppendix,
        appendixFlushCheckpointSize = persistentLevelAppendixFlushCheckpointSize,
        pushForward = false,
        cacheKeysOnCreate = cachePersistentLevelKeyValues,
        bloomFilterFalsePositiveRate = bloomFilterFalsePositiveRate,
        throttle = (_) => Throttle(Duration.Zero, maxSegmentsToPush)
      )
}
