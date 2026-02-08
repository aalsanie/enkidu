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

// ------------------------------------------------------------
// CI + release
// ------------------------------------------------------------

subprojects {
  tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
  }
}


tasks.register("verifyNoWorkingTreeChanges") {
  group = "verification"
  description = "Fails if the build modified tracked files (determinism guard)."

  doLast {
    val out = java.io.ByteArrayOutputStream()
    val err = java.io.ByteArrayOutputStream()

    val result = exec {
      isIgnoreExitValue = true
      commandLine("git", "status", "--porcelain")
      standardOutput = out
      errorOutput = err
    }.exitValue

    if (result != 0) {
      error("git status failed (exit=$result):\n" + err.toString(Charsets.UTF_8))
    }

    val status = out.toString(Charsets.UTF_8).trim()
    if (status.isNotEmpty()) {
      error(
        "Working tree is dirty after build/test. This usually means a non-deterministic generator rewrote tracked files.\n" +
          "Please commit the generated changes (if intentional) or make the generation deterministic.\n\n" +
          "git status --porcelain:\n$status"
      )
    }
  }
}

tasks.register("verifyReleaseTag") {
  group = "verification"
  description = "On GitHub Actions tag builds, ensures tag name matches project.version."

  doLast {
    val refType = System.getenv("GITHUB_REF_TYPE")?.trim()?.lowercase()
    val tag = System.getenv("GITHUB_REF_NAME")?.trim()

    if (refType == "tag") {
      if (tag.isNullOrBlank()) error("GITHUB_REF_TYPE=tag but GITHUB_REF_NAME is missing")

      val expected = "v${project.version}"
      if (tag != expected) {
        error(
          "Release tag/version mismatch.\n" +
            "Tag:      $tag\n" +
            "Expected: $expected\n\n" +
            "Fix: bump project.version in build.gradle.kts or retag with the correct version."
        )
      }

      // Disallow SNAPSHOT on tags.
      if (project.version.toString().contains("SNAPSHOT", ignoreCase = true)) {
        error("Release tags must not build SNAPSHOT versions. Current version: ${project.version}")
      }
    }
  }
}


tasks.register("spotlessCheckAll") {
  group = "verification"
  description = "Runs Spotless checks for all subprojects."

  gradle.projectsEvaluated {

    val targets = subprojects.mapNotNull { it.tasks.findByName("spotlessCheck")?.path }
    if (targets.isEmpty()) error("No spotlessCheck tasks found in subprojects.")
    dependsOn(targets)
  }
}

tasks.register("checkAll") {
  group = "verification"
  description = "Runs unit + integration tests for all subprojects."

  gradle.projectsEvaluated {

    val targets = subprojects.mapNotNull { it.tasks.findByName("check")?.path }
    if (targets.isEmpty()) error("No check tasks found in subprojects.")
    dependsOn(targets)
  }
}

// Main CI aggregator.
tasks.register("ci") {
  group = "verification"
  description = "Runs all CI gates (formatting, tests, plugin verification, determinism guard)."

  dependsOn(
    ":spotlessCheckAll",
    ":checkAll",
    ":enkidu-intellij-plugin:pluginVerification",
    ":verifyNoWorkingTreeChanges",
  )
}

