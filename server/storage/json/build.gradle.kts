plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.kotlinxSerialization)
  alias(libs.plugins.redacted)
  alias(libs.plugins.metro)
}

group = "com.vishnurajeevan.libroabs"


dependencies {
  implementation(project(":server:models"))
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines)
}
