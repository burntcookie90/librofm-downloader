plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.sqldelight)
  alias(libs.plugins.metro)
}

sqldelight {
  databases {
    create("Database") {
      packageName.set("com.vishnurajeevan.libroabs.db")
      verifyMigrations.set(true)
      schemaOutputDirectory.set(File("src/main/sqldelight/schema"))
    }
  }
}

dependencies {
  implementation(project(":server:models"))
  implementation(libs.kotlinx.coroutines)
  implementation(libs.sqldelight.driver)
}