import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform") version "2.11.0"
}

kotlin {
    // IntelliJ 2024.3 requires Java 21.
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

    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.11.0")

    intellijPlatform {
        create("IC", "2024.2.5")
        testFramework(TestFrameworkType.Platform)

        // com.intellij.java is bundled with IntelliJ. Using bundledPlugin avoids the "version required" error.
        bundledPlugin("com.intellij.java")
    }
}


// IntelliJ Platform bundles Kotlin; avoid classpath conflicts by excluding Kotlin stdlib from the plugin artifact.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
}

intellijPlatform {
    pluginConfiguration {
        name = "Enkidu Linkage Doctor"
        version = project.version.toString()
    }


pluginVerification {
    ides {
        // Verify against JetBrains' recommended IDE set for this plugin configuration.
        // This satisfies the Plugin Verifier requirement and keeps CI stable across platform versions.
        recommended()
    }
}
}

tasks {
    withType<KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }

    test {
        useJUnitPlatform()
    }
}
