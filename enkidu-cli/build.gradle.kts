plugins {
    kotlin("jvm") version "2.0.21"
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":enkidu-artifacts"))
    implementation(project(":enkidu-core"))

    implementation("info.picocli:picocli:4.7.6")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
}

application {
    mainClass = "io.enkidu.cli.EnkiduCli"
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("io.enkidu.cli.EnkiduCli")
}
