package com.vishnurajeevan.libroabs.db.writer

interface DbWriter {
  suspend fun write(write: DbWrite)
}