plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {

    implementation(project(":enkidu-artifacts"))
    testImplementation("com.networknt:json-schema-validator:3.0.6")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
}

tasks.test {
    useJUnitPlatform()
}
