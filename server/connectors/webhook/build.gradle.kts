plugins {
  alias(libs.plugins.ksp)
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.kotlinxSerialization)
  alias(libs.plugins.ktorfit)
}

group = "com.vishnurajeevan.libroabs"

dependencies {
  implementation(project(":server:models"))
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.cio)
  implementation(libs.ktor.client.logging)
  implementation(libs.ktor.client.content.negotiation)
  implementation(libs.ktor.serialization.kotlinx)
  implementation(libs.ktorfit.lib)
  implementation(libs.ktorfit.response)

  ksp(libs.kotlin.inject.compiler)
  implementation(libs.kotlin.inject.runtime)
  ksp(libs.kotlin.inject.anvil.compiler)
  implementation(libs.kotlin.inject.anvil.runtime)
  implementation(libs.kotlin.inject.anvil.runtime.optional)
}
