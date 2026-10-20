plugins {
    kotlin("jvm") version "2.4.10" apply false
    kotlin("multiplatform") version "2.4.10" apply false
    kotlin("plugin.serialization") version "2.4.10" apply false
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0" apply false
}

allprojects {
    group = "dev.codenb"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
}
