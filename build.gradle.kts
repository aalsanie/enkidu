plugins {
  kotlin("jvm") version "2.0.21" apply false
  id("com.diffplug.spotless") version "8.1.0" apply false
}

allprojects {
  group = "io.enkidu"
  version = "0.1.1"

  repositories {
    mavenCentral()
  }
}

subprojects {
  apply(plugin = "com.diffplug.spotless")

  extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
    kotlin {
      ktlint()
      licenseHeaderFile(rootProject.file("spotless/HEADER.kt"), "package ")
    }
    kotlinGradle {
      ktlint()
    }
  }
}
