import java.nio.file.Files

plugins {
    base
    java
    idea
}

allprojects {
    repositories {
        mavenCentral()
    }
}


java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Keep debug info so Enkidu can pick up line numbers.
    options.compilerArgs.add("-g")
}


subprojects {
    plugins.withId("java") {
        the<JavaPluginExtension>().toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        // Keep debug info so Enkidu can pick up line numbers.
        options.compilerArgs.add("-g")
    }
}

/**
 * Writes deterministic classpath manifests (one entry per line) under build/enkidu/.
 *
 * These manifests are meant to be used with Enkidu CLI:
 *   enkidu doctor --targets app/build/libs/app.jar --classpath build/enkidu/<scenario>.classpath.txt --out build/enkidu/out/<scenario> --format json,html --fail-on error
 *
 * NOTE: The classpath order is the point of the pit-project.
 */
val enkiduWriteManifests = tasks.register("enkiduWriteManifests") {
    group = "verification"
    description = "Generates Enkidu pit-project classpath manifests under build/enkidu/."

    // Make sure the jars exist.
    dependsOn(
        ":app:jar",
        ":lib-api-v1:jar",
        ":lib-runtime-v2:jar",
        ":lib-runtime-restrict:jar",
        ":lib-shadow-good:jar",
        ":spi-api:jar",
        ":spi-provider-good:jar",
        ":spi-provider-broken:jar",
    )

    doLast {
        val outDir = layout.buildDirectory.dir("enkidu").get().asFile
        outDir.mkdirs()

        fun jarPath(projectPath: String): String {
            val p = project(projectPath)
            val jarTask = p.tasks.named("jar", Jar::class.java).get()
            return jarTask.archiveFile.get().asFile.absolutePath
        }

        val appJar = jarPath(":app")
        val apiV1 = jarPath(":lib-api-v1")
        val runtimeV2 = jarPath(":lib-runtime-v2")
        val restrict = jarPath(":lib-runtime-restrict")
        val shadowGood = jarPath(":lib-shadow-good")
        val spiApi = jarPath(":spi-api")
        val spiGood = jarPath(":spi-provider-good")
        val spiBroken = jarPath(":spi-provider-broken")

        fun write(name: String, entries: List<String>) {
            val file = outDir.resolve(name)
            // Canonicalize + keep order stable.
            val normalized = entries.map { it.trim() }.filter { it.isNotEmpty() }
            Files.writeString(file.toPath(), normalized.joinToString(System.lineSeparator(), postfix = System.lineSeparator()))
        }

        // Scenario 1: method signature mismatch / missing method (NoSuchMethodError style)
        // app compiled vs lib-api-v1; runtime uses lib-runtime-v2 (drops foo(), keeps foo(int)).
        write(
            "missing-method.classpath.txt",
            listOf(
                appJar,
                runtimeV2,
                spiApi,
                spiGood,
            ),
        )

        // Scenario 2: illegal access risk (IllegalAccessError style)
        // runtime keeps foo() but makes it package-private.
        write(
            "illegal-access.classpath.txt",
            listOf(
                appJar,
                restrict,
                spiApi,
                spiGood,
            ),
        )

        // Scenario 3: duplicate class shadowing (classpath order decides the winner)
        // Winner first: runtimeV2 (broken). Shadowed second: lib-shadow-good (correct), but ignored at runtime.
        write(
            "shadowing.classpath.txt",
            listOf(
                appJar,
                runtimeV2,
                shadowGood,
                spiApi,
                spiGood,
            ),
        )

        // Scenario 4: SPI / ServiceLoader broken provider (missing class referenced by META-INF/services).
        write(
            "spi-broken.classpath.txt",
            listOf(
                appJar,
                apiV1,
                spiApi,
                spiBroken,
            ),
        )

        // Compare mode: A is "works"; B introduces regressions.
        write(
            "compareA.classpath.txt",
            listOf(
                appJar,
                apiV1,
                spiApi,
                spiGood,
            ),
        )

        write(
            "compareB.classpath.txt",
            listOf(
                appJar,
                runtimeV2,
                spiApi,
                spiGood,
            ),
        )

        println("Wrote Enkidu manifests under: ${outDir.absolutePath}")
    }
}

tasks.register("enkiduPit") {
    group = "verification"
    description = "Builds the pit-project and writes Enkidu manifests."
    dependsOn("build", enkiduWriteManifests)
}


idea {
    module {
        // Ensure IntelliJ has an explicit compiler output path (useful for Enkidu IntelliJ plugin scans).
        inheritOutputDirs = false
        outputDir = file("${layout.buildDirectory.get().asFile}/idea-classes/main")
        testOutputDir = file("${layout.buildDirectory.get().asFile}/idea-classes/test")
    }
}
