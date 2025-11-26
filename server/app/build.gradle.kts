plugins {
  alias(libs.plugins.ksp)
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.kotlinxSerialization)
  alias(libs.plugins.ktor)
  alias(libs.plugins.ktorfit)
  application
}

group = "com.vishnurajeevan.libroabs"

version = "1.0.0"

application {
  mainClass.set("com.vishnurajeevan.libroabs.LibroDownloaderKt")
  applicationDefaultJvmArgs = listOf("-Dio.ktor.development=${extra["io.ktor.development"] ?: "false"}")
}

dependencies {
  implementation(project(":server:models"))
  implementation(project(":server:server"))
  implementation(project(":server:connectors:tracker:hardcover"))
  implementation(project(":server:connectors:healthcheck:hcio"))
  implementation(project(":server:connectors:webhook"))
  implementation(project(":server:connectors:converter:ffmpeg"))
  implementation(project(":server:storage:json"))
  implementation(project(":server:storage:db"))
  implementation(libs.cardiologist)
  implementation(libs.clikt)
  implementation(libs.logback)
  implementation(libs.jaudiotagger)
  implementation(libs.ffmpeg)
  implementation(libs.kotlinx.io)
  implementation(libs.kotlinx.datetime)
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.cio)
  implementation(libs.ktor.client.logging)
  implementation(libs.ktor.client.content.negotiation)
  implementation(libs.ktor.html)
  implementation(libs.ktor.serialization.kotlinx)
  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.content.negotiation)
  implementation(libs.ktor.server.netty)
  implementation(libs.ktor.server.resources)
  implementation(libs.ktorfit.lib)
  implementation(libs.ktorfit.response)

  ksp(libs.kotlin.inject.compiler)
  implementation(libs.kotlin.inject.runtime)
  ksp(libs.kotlin.inject.anvil.compiler)
  implementation(libs.kotlin.inject.anvil.runtime)
  implementation(libs.kotlin.inject.anvil.runtime.optional)

  testImplementation(libs.kotlin.test.junit)
}