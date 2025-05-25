plugins {
  alias(libs.plugins.ksp)
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.kotlinxSerialization)
  alias(libs.plugins.ktorfit)
  alias(libs.plugins.metro)
}

group = "com.vishnurajeevan.libroabs"

dependencies {
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.cio)
  implementation(libs.ktor.client.logging)
  implementation(libs.ktor.client.content.negotiation)
  implementation(libs.ktor.serialization.kotlinx)
  implementation(libs.ktorfit.lib)
  implementation(libs.ktorfit.response)
}