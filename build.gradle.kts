plugins {
  kotlin("jvm") version "2.0.21" apply false
  id("com.diffplug.spotless") version "8.1.0" apply false
}

allprojects {
  group = "io.enkidu"
  version = "0.2.0"

  repositories {
    mavenCentral()
  }
}

subprojects {
  plugins.withId("org.jetbrains.kotlin.jvm") {
    the<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>().jvmToolchain(21)
  }

  plugins.withType<JavaPlugin> {
    the<JavaPluginExtension>().toolchain {
      languageVersion.set(JavaLanguageVersion.of(21))
    }
  }

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
