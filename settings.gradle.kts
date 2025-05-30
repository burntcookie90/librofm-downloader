rootProject.name = "libro-abs"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
  repositories {
    google {
      mavenContent {
        includeGroupAndSubgroups("androidx")
        includeGroupAndSubgroups("com.android")
        includeGroupAndSubgroups("com.google")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositories {
    google {
      mavenContent {
        includeGroupAndSubgroups("androidx")
        includeGroupAndSubgroups("com.android")
        includeGroupAndSubgroups("com.google")
      }
    }
    mavenCentral()
  }
}

include(":server:models")
include(":server:storage:db")
include(":server:storage:json")
include(":server:app")
include(":server:connectors:tracker:api")
include(":server:connectors:tracker:hardcover")
include(":server:connectors:healthcheck:hcio")
include(":server:connectors:converter:ffmpeg")
include(":server:server")
