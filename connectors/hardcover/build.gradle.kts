plugins {
  alias(libs.plugins.kotlinJvm)
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
  api(project(":connectors:api"))
  implementation(libs.apollo.runtime)
  implementation(libs.apollo.adapters.kotlinx.datetime)
}