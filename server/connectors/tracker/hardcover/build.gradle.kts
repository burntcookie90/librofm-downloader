plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.ksp)
  alias(libs.plugins.apollo)
}

group = "com.vishnurajeevan.libroabs"

apollo {
  service("service") {
    packageName.set("com.vishnurajeevan.hardcover")
    introspection {
      endpointUrl.set("https://api.hardcover.app/v1/graphql")
      headers.put("Authorization", "Bearer ${System.getenv("HARDCOVER_TOKEN")}")
      schemaFile.set(file("src/main/graphql/com/vishnurajeevan/hardcover/schema.graphqls"))
    }
    generateAsInternal.set(true)
    mapScalar("date", "kotlinx.datetime.LocalDate")
    generateOptionalOperationVariables.set(false)
  }
}

dependencies {
  implementation(project(":server:models"))
  api(project(":server:connectors:tracker:api"))
  implementation(libs.apollo.runtime)
  implementation(libs.apollo.adapters.kotlinx.datetime)

  ksp(libs.kotlin.inject.compiler)
  implementation(libs.kotlin.inject.runtime)
  ksp(libs.kotlin.inject.anvil.compiler)
  implementation(libs.kotlin.inject.anvil.runtime)
  implementation(libs.kotlin.inject.anvil.runtime.optional)
}