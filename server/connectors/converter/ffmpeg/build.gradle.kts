plugins {
  alias(libs.plugins.ksp)
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.metro)
}

dependencies {
  implementation(project(":server:models"))
  implementation(libs.kotlinx.datetime)
  implementation(libs.jaudiotagger)
  implementation(libs.ffmpeg)
}