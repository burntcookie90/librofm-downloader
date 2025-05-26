plugins {
  alias(libs.plugins.ksp)
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.kotlinxSerialization)
  alias(libs.plugins.ktor)
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

  ksp(libs.kotlin.inject.compiler)
  implementation(libs.kotlin.inject.runtime)
  ksp(libs.kotlin.inject.anvil.compiler)
  implementation(libs.kotlin.inject.anvil.runtime)
  implementation(libs.kotlin.inject.anvil.runtime.optional)
}