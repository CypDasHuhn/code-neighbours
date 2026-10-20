plugins {
    application
}

dependencies {
    implementation(project(":api"))
    implementation(project(":core"))
    implementation(project(":frontend"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("dev.codenb.server.MainKt")
}
