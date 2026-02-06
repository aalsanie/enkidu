plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {

    api("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    api("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
}

tasks.test {
    useJUnitPlatform()
}

/**
 * guardrails for the public artifacts contract.
 *
 * - Enforces schema namespaces: io/enkidu/artifacts/v<NUMBER>/...
 * - Ensures the primary schema namespace v1 exists (until v2 is introduced intentionally)
 */
tasks.register("validateArtifactsVersioning") {
    group = "verification"
    description = "Validates versioning layout for enkidu-artifacts public contract."

    doLast {
        val srcRoot =
            project.layout.projectDirectory
                .dir("src/main/kotlin/io/enkidu/artifacts")
                .asFile
        if (!srcRoot.exists()) {
            throw GradleException("Expected artifacts sources at: ${srcRoot.path}")
        }

        val schemaDirs = srcRoot.listFiles { f -> f.isDirectory && Regex("^v\\d+$").matches(f.name) }?.toList().orEmpty()
        if (schemaDirs.isEmpty()) {
            throw GradleException(
                "No schema version directories found under ${srcRoot.path}. " +
                    "Expected io/enkidu/artifacts/v<NUMBER>/... (e.g., v1).",
            )
        }

        val v1 = schemaDirs.firstOrNull { it.name == "v1" }
        if (v1 == null) {
            throw GradleException(
                "Schema v1 is missing under ${srcRoot.path}. " +
                    "If you intended to introduce v2, keep v1 for backward compatibility.",
            )
        }

        // Ensure there is at least one Kotlin file under v1 (a real contract).
        val v1Kts = v1.walkTopDown().any { it.isFile && it.extension == "kt" }
        if (!v1Kts) {
            throw GradleException("Schema v1 directory exists but contains no .kt files: ${v1.path}")
        }

        // Hard fail on any sources directly under io/enkidu/artifacts without a schema dir.
        val directKotlinFiles = srcRoot.listFiles { f -> f.isFile && f.extension == "kt" }?.toList().orEmpty()
        if (directKotlinFiles.isNotEmpty()) {
            val names = directKotlinFiles.joinToString { it.name }
            throw GradleException(
                "Unversioned contract files found directly under io/enkidu/artifacts: $names. " +
                    "Move them under io/enkidu/artifacts/v<NUMBER>/.",
            )
        }
    }
}

tasks.named("check") {
    dependsOn("validateArtifactsVersioning")
}
