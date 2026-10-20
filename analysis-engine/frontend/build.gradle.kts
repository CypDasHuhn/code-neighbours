dependencies {
    implementation(project(":api"))
    implementation(project(":core"))
    implementation(project(":lsp"))
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.24.0")
    testImplementation(kotlin("test"))
}
