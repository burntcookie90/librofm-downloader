plugins {
  // this is necessary to avoid the plugins to be loaded multiple times
  // in each subproject's classloader
  alias(libs.plugins.apollo) apply false
  alias(libs.plugins.ksp) apply false
  alias(libs.plugins.kotlinJvm) apply false
  alias(libs.plugins.kotlinMultiplatform) apply false
  alias(libs.plugins.kotlinxSerialization) apply false
  alias(libs.plugins.ktorfit) apply false
  alias(libs.plugins.redacted) apply false
}