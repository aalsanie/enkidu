import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform") version "2.18.1"
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
    testImplementation(platform("org.junit:junit-bom:6.1.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:6.1.2")

    intellijPlatform {
        create("IC", "2024.2.5")
        testFramework(TestFrameworkType.Platform)

        // com.intellij.java is bundled with IntelliJ. Using bundledPlugin avoids the "version required" error.
        bundledPlugin("com.intellij.java")
    }
}

intellijPlatform {
    pluginConfiguration {
        name = "Enkidu Linkage Doctor"
        version = project.version.toString()
    }
    pluginVerification {
        ides {
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

// ------------------------------------------------------------
// CI gate: IntelliJ plugin verification
// ------------------------------------------------------------
tasks.register("pluginVerification") {
    group = "verification"
    description = "Runs IntelliJ plugin verification tasks (structure + plugin verifier)."

    // Always build the plugin ZIP first.
    val buildPluginTask = tasks.findByName("buildPlugin")
    if (buildPluginTask != null) {
        dependsOn(buildPluginTask)
    }

    listOf("verifyPlugin", "runPluginVerifier", "verifyPluginStructure").forEach { name ->
        val t = tasks.findByName(name)
        if (t != null) {
            dependsOn(t)
        }
    }
}
