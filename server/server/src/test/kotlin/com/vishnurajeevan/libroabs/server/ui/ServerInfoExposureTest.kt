package com.vishnurajeevan.libroabs.server.ui

import com.vishnurajeevan.libroabs.models.server.ServerInfo
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ServerInfoExposureTest {
  // ServerInfo holds credentials, so it must never be respondable as JSON (upstream's /info leaked the Libro password).
  @Test
  fun serverInfoHasNoSerializer() {
    assertFailsWith<SerializationException> { serializer<ServerInfo>() }
  }
}
