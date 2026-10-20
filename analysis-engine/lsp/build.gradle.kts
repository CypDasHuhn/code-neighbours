dependencies {
    implementation(project(":api"))
    implementation(project(":core"))
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.24.0")
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    val testSourceSet = sourceSets.named("test")
    doFirst {
        systemProperty("codenb.testClasspath", testSourceSet.get().runtimeClasspath.asPath)
    }
}
