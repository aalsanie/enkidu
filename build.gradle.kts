import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
  kotlin("jvm") version "2.4.10" apply false
  id("com.diffplug.spotless") version "8.9.0" apply false
}

allprojects {
  group = "io.enkidu"
  version = "0.3.0"

  repositories {
    mavenCentral()
  }
}

subprojects {
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

tasks.register("printVersion") {
  group = "help"
  description = "Prints the exact project version."
  doLast {
    println(project.version)
  }
}

tasks.register("verifyNoWorkingTreeChanges") {
  group = "verification"
  description = "Fails if a build or generator modified tracked or untracked repository files."

  doLast {
    val process =
      ProcessBuilder(
        listOf(
          "git",
          "status",
          "--porcelain=v1",
          "--untracked-files=all",
        ),
      )
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()

    val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    val exitCode = process.waitFor()

    if (exitCode != 0) {
      error("git status failed (exit=$exitCode):\n$output")
    }

    val status = output.trim()
    if (status.isNotEmpty()) {
      error(
        "Working tree is dirty after build/test.\n" +
          "Commit intentional generated changes or make generation deterministic.\n\n" +
          "git status --porcelain=v1 --untracked-files=all:\n$status",
      )
    }
  }
}

tasks.register("verifyReleaseTag") {
  group = "verification"
  description = "Validates release tag, version, and changelog discipline."

  doLast {
    val refType = System.getenv("GITHUB_REF_TYPE")?.trim()?.lowercase()
    val tag = System.getenv("GITHUB_REF_NAME")?.trim()

    if (refType != "tag") {
      return@doLast
    }

    if (tag.isNullOrBlank()) {
      error("GITHUB_REF_TYPE=tag but GITHUB_REF_NAME is missing")
    }

    val releaseVersion = project.version.toString()
    val expectedTag = "v$releaseVersion"

    if (tag != expectedTag) {
      error(
        "Release tag/version mismatch.\n" +
          "Tag:      $tag\n" +
          "Expected: $expectedTag\n\n" +
          "Bump project.version or use the matching release tag.",
      )
    }

    if (releaseVersion.contains("SNAPSHOT", ignoreCase = true)) {
      error("Release tags must not build SNAPSHOT versions. Current version: $releaseVersion")
    }

    val changelog = rootProject.file("CHANGELOG.md")
    if (!changelog.isFile) {
      error("Missing CHANGELOG.md")
    }

    val heading = Regex("(?m)^##\\s+\\[${Regex.escape(releaseVersion)}\\]\\s*$")
    if (!heading.containsMatchIn(changelog.readText(Charsets.UTF_8))) {
      error("CHANGELOG.md must contain an exact '## [$releaseVersion]' release section.")
    }
  }
}

tasks.register("spotlessCheckAll") {
  group = "verification"
  description = "Runs Spotless checks for all subprojects."
  dependsOn(subprojects.map { "${it.path}:spotlessCheck" })
}

tasks.register("checkAll") {
  group = "verification"
  description = "Runs unit and integration checks for all subprojects."
  dependsOn(subprojects.map { "${it.path}:check" })
}

tasks.register("assembleAll") {
  group = "build"
  description = "Assembles all production artifacts."
  dependsOn(subprojects.map { "${it.path}:assemble" })
}

tasks.named("verifyNoWorkingTreeChanges").configure {
  mustRunAfter(
    ":spotlessCheckAll",
    ":checkAll",
    ":assembleAll",
    ":enkidu-intellij-plugin:pluginVerification",
  )
}

tasks.register("ci") {
  group = "verification"
  description = "Runs formatting, tests, packaging, plugin verification, and determinism gates."

  dependsOn(
    ":spotlessCheckAll",
    ":checkAll",
    ":assembleAll",
    ":enkidu-intellij-plugin:pluginVerification",
    ":verifyNoWorkingTreeChanges",
  )
}
