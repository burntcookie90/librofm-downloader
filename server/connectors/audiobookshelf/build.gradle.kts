plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.kotlinxSerialization)
}

group = "com.vishnurajeevan.libroabs"

dependencies {
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.content.negotiation)
  implementation(libs.ktor.serialization.kotlinx)

  testImplementation(libs.kotlin.test.junit)
  testImplementation(libs.ktor.client.mock)
  testImplementation(libs.ktor.client.cio)
}
