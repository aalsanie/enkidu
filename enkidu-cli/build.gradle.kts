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
    mainClass = "io.enkidu.cli.EnkiduCli"
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("io.enkidu.cli.EnkiduCli")
}
