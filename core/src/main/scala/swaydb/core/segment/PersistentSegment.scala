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

package swaydb.core.segment

import java.nio.file.Path
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicReference

import bloomfilter.mutable.BloomFilter
import com.typesafe.scalalogging.LazyLogging
import swaydb.core.data.Persistent.{CreatedReadOnly, DeletedReadOnly}
import swaydb.core.data.{PersistentReadOnly, _}
import swaydb.core.io.file.DBFile
import swaydb.core.io.reader.Reader
import swaydb.core.level.PathsDistributor
import swaydb.core.segment.format.one.SegmentReader._
import swaydb.core.segment.format.one.{KeyMatcher, SegmentFooter, SegmentReader}
import swaydb.core.util.TryUtil._
import swaydb.core.util._
import swaydb.data.config.Dir
import swaydb.data.slice.{Reader, Slice}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

private[segment] class PersistentSegment(val file: DBFile,
                                         mmapReads: Boolean,
                                         mmapWrites: Boolean,
                                         cacheKeysOnCreate: Boolean,
                                         val minKey: Slice[Byte],
                                         val maxKey: Slice[Byte],
                                         val segmentSize: Int,
                                         val removeDeletes: Boolean)(implicit ordering: Ordering[Slice[Byte]],
                                                                     keyValueLimiter: (PersistentReadOnly, Segment) => Unit,
                                                                     fileOpenLimited: DBFile => Unit,
                                                                     ec: ExecutionContext) extends Segment with LazyLogging {

  import ordering._

  def path = file.path

  private[segment] lazy val cache = new ConcurrentSkipListMap[Slice[Byte], PersistentReadOnly](ordering)
  private[segment] lazy val footer = new AtomicReference[Option[SegmentFooter]](None)

  def populateCacheWithKeys(reader: Reader) =
    Future {
      SegmentReader.readFooter(reader) flatMap {
        footer =>
          this.footer.set(Some(footer))
          readAllReadOnly(footer, reader.copy()) map {
            keyValues =>
              keyValues foreach {
                sliceKeyValue =>
                  addToCache {
                    sliceKeyValue match {
                      case keyValue: CreatedReadOnly =>
                        keyValue.copy(_key = sliceKeyValue.key.unslice(), valueReader = createReader())
                      case keyValue: DeletedReadOnly =>
                        keyValue.copy(_key = sliceKeyValue.key.unslice())
                    }
                  }
              }
          }
      }
    }

  def close: Try[Unit] =
    file.close

  def isOpen: Boolean =
    file.isOpen

  def isFileDefined =
    file.isFileDefined

  private def createReader(): Reader =
    Reader(file)

  private def addToCache(keyValue: PersistentReadOnly): Unit = {
    cache.put(keyValue.key, keyValue)
    keyValueLimiter(keyValue, this)
  }

  def delete: Try[Unit] = {
    logger.trace(s"{}: DELETING FILE", path)
    clearCache()
    footer.set(None)
    file.delete() recoverWith {
      case exception =>
        logger.error(s"{}: Failed to delete Segment file.", path, exception)
        Failure(exception)
    }
  }

  def copyTo(toPath: Path): Try[Path] =
    file copyTo toPath

  /**
    * Default targetPath is set to this [[PersistentSegment]]'s parent directory.
    */
  def put(newKeyValues: Slice[KeyValue],
          minSegmentSize: Long,
          bloomFilterFalsePositiveRate: Double,
          targetPaths: PathsDistributor = PathsDistributor(Seq(Dir(path.getParent, 1)), () => Seq()))(implicit idGenerator: IDGenerator): Try[Slice[Segment]] =
    getAll(bloomFilterFalsePositiveRate) flatMap {
      currentKeyValues =>
        SegmentMerge.merge(
          newKeyValues = newKeyValues,
          currentKeyValues = currentKeyValues,
          minSegmentSize = minSegmentSize,
          forInMemory = false,
          removeDeletes = removeDeletes,
          bloomFilterFalsePositiveRate = bloomFilterFalsePositiveRate
        ).tryMap(
          tryBlock =
            keyValues => {
              Segment.persistent(
                path = targetPaths.next.resolve(idGenerator.nextSegmentID),
                cacheKeysOnCreate = cacheKeysOnCreate,
                mmapReads = mmapReads,
                mmapWrites = mmapWrites,
                keyValues = keyValues,
                bloomFilterFalsePositiveRate = bloomFilterFalsePositiveRate,
                removeDeletes = removeDeletes
              )
            },

          recover =
            (segments: Slice[Segment], _: Failure[Slice[Segment]]) =>
              segments.foreach {
                segmentToDelete =>
                  segmentToDelete.delete.failed.foreach {
                    exception =>
                      logger.error(s"{}: Failed to delete Segment '{}' in recover due to failed put", path, segmentToDelete.path, exception)
                  }
              }
        )
    }

  private def prepareGet[T](getOperation: (SegmentFooter, Reader) => Try[T]): Try[T] =
    getFooter() flatMap {
      footer =>
        getOperation(footer, createReader())
    } recoverWith {
      case ex: Exception =>
        ExceptionUtil.logFailure(s"$path: Failed to read Segment.", ex)
        Failure(ex)
    }

  private def returnResponse(response: Try[Option[PersistentReadOnly]]): Try[Option[PersistentReadOnly]] =
    response flatMap {
      case Some(keyValue) =>
        if (persistent) keyValue.unsliceKey
        addToCache(keyValue)
        response

      case None =>
        response
    }

  def getFooter(): Try[SegmentFooter] =
    footer.get.map(Success(_)) getOrElse {
      SegmentReader.readFooter(createReader()) map {
        segmentFooter =>
          footer.set(Some(segmentFooter))
          segmentFooter
      }
    }

  override def getBloomFilter: Try[BloomFilter[Slice[Byte]]] =
    getFooter().map(_.bloomFilter)

  def getFromCache(key: Slice[Byte]): Option[PersistentReadOnly] =
    Option(cache.get(key))

  def mightContain(key: Slice[Byte]): Try[Boolean] =
    getFooter().map(_.bloomFilter.mightContain(key))

  def get(key: Slice[Byte]): Try[Option[PersistentReadOnly]] =
    if (key < minKey || key > maxKey)
      Success(None)
    else {
      val floorEntry = Option(cache.floorEntry(key))
      floorEntry match {
        case Some(value) if value.getKey equiv key =>
          Success(Some(value.getValue))

        case _ =>
          prepareGet {
            (footer, reader) =>
              if (!footer.bloomFilter.mightContain(key))
                Success(None)
              else
                returnResponse {
                  find(KeyMatcher.Exact(key), startFrom = floorEntry.map(_.getValue), reader, footer)
                }
          }
      }
    }

  private def satisfyLowerFromCache(key: Slice[Byte],
                                    lowerKeyValue: PersistentReadOnly): Option[PersistentReadOnly] =
    Option(cache.ceilingEntry(key)).map(_.getValue) flatMap {
      ceilingKeyValue =>
        if (lowerKeyValue.nextIndexOffset == ceilingKeyValue.indexOffset)
          Some(lowerKeyValue)
        else
          None
    }

  def lower(key: Slice[Byte]): Try[Option[PersistentReadOnly]] =
    if (key < minKey)
      Success(None)
    else if (key > maxKey)
      get(maxKey)
    else {
      val lowerKeyValue = Option(cache.lowerEntry(key)).map(_.getValue)
      val lowerFromCache = lowerKeyValue.flatMap(satisfyLowerFromCache(key, _))
      if (lowerFromCache.isDefined)
        Success(lowerFromCache)
      else
        prepareGet {
          (footer, reader) =>
            returnResponse {
              find(KeyMatcher.Lower(key), startFrom = lowerKeyValue, reader, footer)
            }
        }
    }

  private def satisfyHigherFromCache(key: Slice[Byte],
                                     floorKeyValue: PersistentReadOnly): Option[PersistentReadOnly] =
    Option(cache.higherEntry(key)).map(_.getValue) flatMap {
      higherKeyValue =>
        if (floorKeyValue.nextIndexOffset == higherKeyValue.indexOffset)
          Some(higherKeyValue)
        else
          None
    }

  def higher(key: Slice[Byte]): Try[Option[PersistentReadOnly]] =
    if (key >= maxKey)
      Success(None)
    else if (key < minKey)
      get(minKey)
    else {
      val floorKeyValue = Option(cache.floorEntry(key)).map(_.getValue)
      val higherFromCache = floorKeyValue.flatMap(satisfyHigherFromCache(key, _))

      if (higherFromCache.isDefined)
        Success(higherFromCache)
      else
        prepareGet {
          (footer, reader) =>
            returnResponse {
              find(KeyMatcher.Higher(key), startFrom = floorKeyValue, reader, footer)
            }
        }
    }

  def getAll(bloomFilterFalsePositiveRate: Double, addTo: Option[Slice[Persistent]] = None): Try[Slice[Persistent]] =
    prepareGet {
      (footer, reader) =>
        SegmentReader.readAll(footer, reader, bloomFilterFalsePositiveRate, addTo) recoverWith {
          case exception =>
            logger.trace("{}: Reading index block failed. Segment file is corrupted.", path)
            Failure(exception)
        }
    }

  def existsOnDisk: Boolean =
    file.existsOnDisk

  def existsInMemory: Boolean =
    file.existsInMemory

  def notExistsOnDisk: Boolean =
    !file.existsOnDisk

  def getKeyValueCount(): Try[Int] =
    getFooter().map(_.keyValueCount)

  def memory: Boolean =
    file.memory

  def persistent: Boolean =
    file.persistent

}