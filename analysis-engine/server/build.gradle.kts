plugins {
    application
}

dependencies {
    implementation(project(":api"))
    implementation(project(":core"))
    implementation(project(":frontend"))
    implementation(project(":lsp"))
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.24.0")
    implementation("org.yaml:snakeyaml:2.4")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("dev.codenb.server.MainKt")
}

tasks.withType<Test>().configureEach {
    val testSourceSet = sourceSets.named("test")
    doFirst {
        systemProperty("codenb.testClasspath", testSourceSet.get().runtimeClasspath.asPath)
    }
}
