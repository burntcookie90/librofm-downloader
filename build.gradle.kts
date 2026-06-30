import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions
import org.jetbrains.kotlin.gradle.plugin.KotlinBasePlugin
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
  // this is necessary to avoid the plugins to be loaded multiple times
  // in each subproject's classloader
  alias(libs.plugins.apollo) apply false
  alias(libs.plugins.ksp) apply false
  alias(libs.plugins.kotlinJvm) apply false
  alias(libs.plugins.kotlinMultiplatform) apply false
  alias(libs.plugins.kotlinxSerialization) apply false
  alias(libs.plugins.metro) apply false
  alias(libs.plugins.ktorfit) apply false
  alias(libs.plugins.redacted) apply false
  alias(libs.plugins.sqldelight) apply false
}

subprojects {
  pluginManager.withPlugin("java") {
    configure<JavaPluginExtension> {
      toolchain { languageVersion.set(libs.versions.jdk.map(JavaLanguageVersion::of)) }
    }
    tasks.withType<JavaCompile>().configureEach {
      options.release.set(libs.versions.jvmTarget.map(String::toInt))
    }
  }

  plugins.withType<KotlinBasePlugin> {
    project.tasks.withType<KotlinCompilationTask<*>>().configureEach {
      (compilerOptions as? KotlinJvmCompilerOptions)?.jvmTarget?.set(
        libs.versions.jvmTarget.map(org.jetbrains.kotlin.gradle.dsl.JvmTarget::fromTarget)
      )
    }
  }
}