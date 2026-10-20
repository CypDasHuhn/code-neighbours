plugins {
    kotlin("multiplatform")
}

kotlin {
    js(IR) {
        browser {
            commonWebpackConfig {
                outputFileName = "web.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        jsMain.dependencies {
            implementation(project(":shared"))
        }
    }
}

tasks.matching { it.name == "jsBrowserTest" }.configureEach { enabled = false }
