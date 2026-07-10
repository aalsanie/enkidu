plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {

    api("com.fasterxml.jackson.core:jackson-databind:2.22.1")
    api("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
}

tasks.test {
    useJUnitPlatform()
}
