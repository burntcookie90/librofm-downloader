plugins {
  alias(libs.plugins.ksp)
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.kotlinxSerialization)
  alias(libs.plugins.ktor)
  alias(libs.plugins.metro)
}

dependencies {
  implementation(project(":server:models"))
  implementation(libs.logback)
  implementation(libs.ktor.html)
  implementation(libs.ktor.serialization.kotlinx)
  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.content.negotiation)
  implementation(libs.ktor.server.netty)
  implementation(libs.ktor.server.resources)
}