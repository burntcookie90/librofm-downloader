plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.kotlinxSerialization)
}

group = "com.vishnurajeevan.libroabs"

dependencies {
  api(libs.kotlinx.datetime)
  implementation(libs.kotlinx.serialization.json)
}