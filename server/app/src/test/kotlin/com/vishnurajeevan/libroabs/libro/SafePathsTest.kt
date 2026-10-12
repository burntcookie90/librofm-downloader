package com.vishnurajeevan.libroabs.libro

import com.vishnurajeevan.libroabs.models.server.resolveWithin
import com.vishnurajeevan.libroabs.models.server.toSafePathSegment
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SafePathsTest {
  private val dir = Files.createTempDirectory("safe-paths").toFile().canonicalFile

  @Test
  fun segmentsLoseSeparatorsAndControlCharacters() {
    assertEquals("a-b-c", "a/b\\c".toSafePathSegment())
    assertEquals("title", " ti\u0000tle\n ".toSafePathSegment())
    assertEquals("_", ".".toSafePathSegment())
    assertEquals("__", "..".toSafePathSegment())
    assertEquals("..-x", "../x".toSafePathSegment())
  }

  @Test
  fun resolvesInsideTheDirectory() {
    assertEquals(dir.resolve("part/01.mp3"), dir.resolveWithin("part/01.mp3"))
    assertEquals(dir.resolve("b.mp3"), dir.resolveWithin("a/../b.mp3"))
    // absolute names are treated as relative to the directory
    assertEquals(dir.resolve("etc/passwd"), dir.resolveWithin("/etc/passwd"))
  }

  @Test
  fun refusesPathsThatEscape() {
    assertFailsWith<IllegalArgumentException> { dir.resolveWithin("../evil.mp3") }
    assertFailsWith<IllegalArgumentException> { dir.resolveWithin("a/../../evil.mp3") }
    // a sibling sharing the directory's name as a prefix is still outside it
    assertFailsWith<IllegalArgumentException> { dir.resolveWithin("../${dir.name}-other/x") }
  }
}
