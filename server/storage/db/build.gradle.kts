plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.sqldelight)
  alias(libs.plugins.ksp)
}

sqldelight {
  databases {
    create("Database") {
      packageName.set("com.vishnurajeevan.libroabs.db")
    }
  }
}

dependencies {
  implementation(project(":server:models"))
  implementation(libs.kotlinx.coroutines)
  implementation(libs.sqldelight.driver)

  ksp(libs.kotlin.inject.compiler)
  implementation(libs.kotlin.inject.runtime)
  ksp(libs.kotlin.inject.anvil.compiler)
  implementation(libs.kotlin.inject.anvil.runtime)
  implementation(libs.kotlin.inject.anvil.runtime.optional)
}
