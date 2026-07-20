plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":enkidu-artifacts"))

    implementation("org.ow2.asm:asm:9.10.1")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
}

tasks.test {
    useJUnitPlatform()
}
