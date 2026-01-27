plugins {
    java
}

dependencies {
    implementation(project(":lib-api-v1"))
    implementation(project(":spi-api"))

    // Not packaged into the app jar; Enkidu will resolve it from the runtime classpath manifests.
    runtimeOnly(project(":spi-provider-good"))
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "demo.app.Main"
    }
}
