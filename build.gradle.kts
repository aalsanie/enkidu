import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

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
    the<KotlinJvmProjectExtension>().jvmToolchain(21)
  }

  plugins.withType<JavaPlugin> {
    the<JavaPluginExtension>().toolchain {
      languageVersion.set(JavaLanguageVersion.of(21))
    }
  }

  apply(plugin = "com.diffplug.spotless")

  extensions.configure<SpotlessExtension> {
    kotlin {
      ktlint()
      licenseHeaderFile(rootProject.file("spotless/HEADER.kt"), "package ")
    }
    kotlinGradle {
      ktlint()
    }
  }

  tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
  }
}

// ------------------------------------------------------------
// CI + release (root)
// ------------------------------------------------------------

tasks.register("verifyNoWorkingTreeChanges") {
  group = "verification"
  description = "Fails if the build modified tracked files (determinism guard)."

  doLast {
    val out = java.io.ByteArrayOutputStream()
    val err = java.io.ByteArrayOutputStream()

    // ProviderFactory.exec is the non-deprecated API in Gradle 8+
    val execProvider = providers.exec {
      // Use explicit setter calls to avoid Kotlin-script accessor/overload issues.
      setIgnoreExitValue(true)
      commandLine("git", "status", "--porcelain")
      setStandardOutput(out)
      setErrorOutput(err)
    }

    val result = execProvider.result.get()

    if (result.exitValue != 0) {
      error("git status failed (exit=${result.exitValue}):\n${err.toString(Charsets.UTF_8)}")
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
  description = "On GitHub Actions tag builds, ensures tag name matches project.version and forbids SNAPSHOT."

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

      if (project.version.toString().contains("SNAPSHOT", ignoreCase = true)) {
        error("Release tags must not build SNAPSHOT versions. Current version: ${project.version}")
      }
    }
  }
}

// Aggregate spotless across all subprojects (root has no Spotless tasks).
tasks.register("spotlessCheckAll") {
  group = "verification"
  description = "Runs Spotless checks for all subprojects."
}

// Aggregate unit + integration checks across all subprojects (root has no check task).
tasks.register("checkAll") {
  group = "verification"
  description = "Runs unit + integration tests for all subprojects."
}

/**
 * Gradle 8.13+ correctness:
 * Do NOT call gradle.projectsEvaluated { ... } from inside task configuration blocks.
 */
gradle.projectsEvaluated {
  tasks.named("spotlessCheckAll").configure {
    val targets = subprojects.mapNotNull { sp -> sp.tasks.findByName("spotlessCheck")?.path }
    if (targets.isEmpty()) error("No spotlessCheck tasks found in subprojects.")
    dependsOn(targets)
  }

  tasks.named("checkAll").configure {
    val targets = subprojects.mapNotNull { sp -> sp.tasks.findByName("check")?.path }
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
