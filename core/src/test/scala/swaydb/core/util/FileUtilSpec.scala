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

package swaydb.core.util

import java.nio.file.Paths

import swaydb.core.TestBase
import swaydb.core.io.file.IO

class FileUtilSpec extends TestBase {

  "FileUtil.fileId" should {
    "get the file id" in {
      FileUtil.fileId(Paths.get("/one/1.log")).assertGet shouldBe(1, Extension.Log)
      FileUtil.fileId(Paths.get("/one/two/10.log")).assertGet shouldBe(10, Extension.Log)
      FileUtil.fileId(Paths.get("/one/two/three/1000.seg")).assertGet shouldBe(1000, Extension.Seg)
    }
  }

  "FileUtil.folderId" should {
    "get the folderId" in {
      FileUtil.folderId(Paths.get("/one/1")) shouldBe 1
      FileUtil.folderId(Paths.get("/one/two/10")) shouldBe 10
      FileUtil.folderId(Paths.get("/one/two/three/1000")) shouldBe 1000
    }
  }

  "FileUtil.incrementFileId" should {
    "return a new file path with incremented file id" in {
      FileUtil.incrementFileId(Paths.get("/one/1.log")).assertGet shouldBe Paths.get("/one/2.log")
      FileUtil.incrementFileId(Paths.get("/one/two/10.log")).assertGet shouldBe Paths.get("/one/two/11.log")
      FileUtil.incrementFileId(Paths.get("/one/two/three/1000.seg")).assertGet shouldBe Paths.get("/one/two/three/1001.seg")
    }
  }

  "FileUtil.incrementFolderId" should {
    "return a new file path with incremented folder id" in {
      FileUtil.incrementFolderId(Paths.get("/one/1")) shouldBe Paths.get("/one/2")
      FileUtil.incrementFolderId(Paths.get("/one/two/10")) shouldBe Paths.get("/one/two/11")
      FileUtil.incrementFolderId(Paths.get("/one/two/three/1000")) shouldBe Paths.get("/one/two/three/1001")
    }
  }

  "FileUtil.files" should {
    "fetch all the files in sorted order" in {
      val dir = createRandomIntDirectory
      val actual =
        Seq(
          dir.resolve(s"1.${Extension.Log}"),
          dir.resolve(s"4.${Extension.Log}"),
          dir.resolve(s"99.${Extension.Log}"),
          dir.resolve(s"2.${Extension.Log}"),
          dir.resolve(s"299.${Extension.Log}"),
          dir.resolve(s"3.${Extension.Log}"),
          dir.resolve(s"10.${Extension.Log}"),
          dir.resolve(s"33.${Extension.Log}")
        )
      actual.foreach {
        path =>
          IO.createFile(path).assertGet
      }

      val expect =
        Seq(
          dir.resolve(s"1.${Extension.Log}"),
          dir.resolve(s"2.${Extension.Log}"),
          dir.resolve(s"3.${Extension.Log}"),
          dir.resolve(s"4.${Extension.Log}"),
          dir.resolve(s"10.${Extension.Log}"),
          dir.resolve(s"33.${Extension.Log}"),
          dir.resolve(s"99.${Extension.Log}"),
          dir.resolve(s"299.${Extension.Log}")
        )

      FileUtil.files(dir, Extension.Log) shouldBe expect
    }
  }

  "FileUtil.folders" should {
    "fetch all the folders in sorted order" in {
      val dir = createRandomIntDirectory
      val actual =
        Seq(
          dir.resolve("1"),
          dir.resolve("10"),
          dir.resolve("7"),
          dir.resolve("15"),
          dir.resolve("7676"),
          dir.resolve("123"),
          dir.resolve("0"),
          dir.resolve("5454")
        )
      actual.foreach {
        path =>
          IO.createDirectoryIfAbsent(path)
      }

      val expect =
        Seq(
          dir.resolve("0"),
          dir.resolve("1"),
          dir.resolve("7"),
          dir.resolve("10"),
          dir.resolve("15"),
          dir.resolve("123"),
          dir.resolve("5454"),
          dir.resolve("7676")
        )

      FileUtil.folders(dir) shouldBe expect
    }
  }
}
