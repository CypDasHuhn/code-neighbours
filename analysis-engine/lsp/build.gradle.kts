dependencies {
    implementation(project(":api"))
    implementation(project(":core"))
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.24.0")
    testImplementation(kotlin("test"))
}
