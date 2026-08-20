plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {

    implementation(project(":enkidu-artifacts"))
    testImplementation("com.networknt:json-schema-validator:1.5.6")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
}

tasks.test {
    useJUnitPlatform()
}
