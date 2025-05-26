plugins {
  alias(libs.plugins.ksp)
  alias(libs.plugins.kotlinJvm)
}

dependencies {
  implementation(project(":server:models"))
  implementation(libs.kotlinx.datetime)
  implementation(libs.jaudiotagger)
  implementation(libs.ffmpeg)

  ksp(libs.kotlin.inject.compiler)
  implementation(libs.kotlin.inject.runtime)
  ksp(libs.kotlin.inject.anvil.compiler)
  implementation(libs.kotlin.inject.anvil.runtime)
  implementation(libs.kotlin.inject.anvil.runtime.optional)
}