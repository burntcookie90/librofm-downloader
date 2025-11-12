plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.kotlinxSerialization)
  alias(libs.plugins.ksp)
  alias(libs.plugins.redacted)
}

group = "com.vishnurajeevan.libroabs"

dependencies {
  api(libs.kotlinx.datetime)
  implementation(libs.kotlinx.serialization.json)

  implementation(libs.kotlinx.coroutines)

  ksp(libs.kotlin.inject.compiler)
  implementation(libs.kotlin.inject.runtime)
  ksp(libs.kotlin.inject.anvil.compiler)
  implementation(libs.kotlin.inject.anvil.runtime)
  implementation(libs.kotlin.inject.anvil.runtime.optional)
}