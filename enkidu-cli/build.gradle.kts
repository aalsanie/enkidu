import java.util.jar.JarFile
import org.gradle.api.tasks.bundling.Jar

plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":enkidu-artifacts"))
    implementation(project(":enkidu-core"))
    implementation(project(":enkidu-export"))

    implementation("info.picocli:picocli:4.7.6")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
}

application {
    mainClass.set("io.enkidu.cli.EnkiduCli")
}

tasks.withType<Jar>().configureEach {
    manifest {
        attributes(
            "Implementation-Title" to "Enkidu Linkage Doctor CLI",
            "Implementation-Version" to project.version.toString(),
            "Main-Class" to application.mainClass.get(),
        )
    }
}

tasks.test {
    useJUnitPlatform()
}

val verifyCliJarMetadata =
    tasks.register("verifyCliJarMetadata") {
        group = "verification"
        description = "Verifies release-critical CLI JAR manifest metadata."
        dependsOn(tasks.named("jar"))

        doLast {
            val cliJar = tasks.named<Jar>("jar").get().archiveFile.get().asFile

            JarFile(cliJar).use { jar ->
                val attributes = jar.manifest?.mainAttributes ?: error("CLI JAR is missing META-INF/MANIFEST.MF")
                val implementationVersion = attributes.getValue("Implementation-Version")
                val mainClass = attributes.getValue("Main-Class")

                check(implementationVersion == project.version.toString()) {
                    "CLI JAR Implementation-Version was '$implementationVersion'; expected '${project.version}'."
                }
                check(mainClass == application.mainClass.get()) {
                    "CLI JAR Main-Class was '$mainClass'; expected '${application.mainClass.get()}'."
                }
            }
        }
    }

tasks.named("check") {
    dependsOn(verifyCliJarMetadata)
}
