package com.vishnurajeevan.libroabs.models.server

import java.io.File

private val separators = Regex("""[/\\]""")
private val controlChars = Regex("""[\x00-\x1F]""")

/**
 * Makes untrusted text (book metadata, server-supplied file names) safe to use as a single path segment:
 * separators become `-`, control characters are dropped and `.`/`..` can't be used to climb directories.
 */
fun String.toSafePathSegment(): String {
  val cleaned = replace(separators, "-").replace(controlChars, "").trim()
  return if (cleaned == "." || cleaned == "..") cleaned.replace('.', '_') else cleaned
}

/** Resolves [relative] under this directory, refusing anything that would end up outside it. */
fun File.resolveWithin(relative: String): File {
  val base = canonicalFile
  val target = File(base, relative).canonicalFile
  require(target.toPath().startsWith(base.toPath())) { "Refusing to write outside $base: $relative" }
  return target
}
