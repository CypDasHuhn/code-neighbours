plugins {
    application
}

dependencies {
    implementation(project(":api"))
    implementation(project(":core"))
    implementation(project(":frontend"))
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("dev.codenb.cli.MainKt")
}
