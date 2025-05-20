plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.kotlinxSerialization)
}

group = "com.vishnurajeevan.libroabs"


dependencies {
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines)
}