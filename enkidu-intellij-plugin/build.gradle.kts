import org.gradle.api.tasks.testing.Test
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform") version "2.10.5"
}

kotlin {
    // IntelliJ Platform 2024.3 requires Java 21
    jvmToolchain(21)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":enkidu-artifacts"))
    implementation(project(":enkidu-core"))
    implementation(project(":enkidu-export"))

    intellijPlatform {
        intellijIdeaCommunity("2024.3")

        bundledPlugin("com.intellij.java")

        // Use IntelliJ Platform test framework, but we intentionally run Gradle tests with JUnit4 (see below)
        testFramework(TestFrameworkType.Platform)
    }

    // IMPORTANT:
    // Do NOT add JUnit5 / junit-platform dependencies here.
    // Gradle 9 + IntelliJ's JUnit5 listener can fail early with:
    // "Provider com.intellij.tests.JUnit5TestSessionListener could not be instantiated".
    //
    // We force JUnit4 for the Gradle test task to avoid JUnit Platform bootstrapping entirely.
    testImplementation(kotlin("test-junit"))
}

intellijPlatform {
    pluginConfiguration {
        name = "Enkidu Linkage Doctor"
        version = project.version.toString()
        vendor {
            name = "Enkidu"
            url = "https://github.com/aalsanie"
        }
        description =
            """
            Enkidu Linkage Doctor checks whether the code you compiled will still work at runtime by reading your compiled bytecode
            and resolving every referenced class, method, and field against the exact jars on your runtime classpath.
            """.trimIndent()
        changeNotes = "Read-only IntelliJ UI (Milestone J)."
    }

    buildSearchableOptions = false
}

tasks.withType<Test>().configureEach {
    useJUnit()
}
