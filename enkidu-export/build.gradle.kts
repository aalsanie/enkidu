plugins {
    kotlin("jvm") version "2.0.21"
}

kotlin {
    jvmToolchain(17)
}

dependencies {

    implementation(project(":enkidu-artifacts"))
    testImplementation("com.networknt:json-schema-validator:1.5.6")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
}

tasks.test {
    useJUnitPlatform()
}
