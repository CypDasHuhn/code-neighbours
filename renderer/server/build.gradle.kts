plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

dependencies {
    implementation(project(":shared"))
    implementation("io.ktor:ktor-server-core-jvm:3.3.2")
    implementation("io.ktor:ktor-server-netty-jvm:3.3.2")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:3.3.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:3.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("dev.codenb.renderer.server.MainKt")
}

tasks.withType<Test>().configureEach {
    testLogging {
        showStandardStreams = true
    }
}
