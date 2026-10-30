import groovy.json.JsonSlurper

pluginManagement {
    repositories {
        exclusiveContent {
            forRepository { maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/") }
            filter { includeGroup("dev.drewhamilton.poko") }
        }
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-develop/")
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("org.jetbrains.kotlin.multiplatform") version "2.4.20-0.1.0-08"
        id("org.jetbrains.kotlin.plugin.compose") version "2.4.20-0.1.0-08"
        id("dev.drewhamilton.poko") version "0.23.2"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository { maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/") }
            filter { includeGroup("dev.drewhamilton.poko") }
        }
        // Ktor 整组固定同一个候选源，禁止同组跨仓拼接。
        exclusiveContent {
            forRepository { maven("https://maven.eazytec-cloud.com/nexus/repository/maven-develop/") }
            filter { includeGroup("io.ktor") }
        }
        exclusiveContent {
            forRepository { maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/") }
            filter {
                includeModule("org.jetbrains.kotlinx", "kotlinx-io-okio")
                includeModule("org.jetbrains.kotlinx", "kotlinx-io-okio-ohosx64")
                includeModule("org.jetbrains.kotlinx", "kotlinx-io-okio-ohosarm64")
            }
        }
        google {
            content {
                includeModule("androidx.annotation", "annotation")
                includeModule("androidx.arch.core", "core-common")
                includeModule("androidx.collection", "collection")
                includeModule("androidx.compose.runtime", "runtime")
                includeModule("androidx.compose.runtime", "runtime-annotation")
                includeModule("androidx.compose.runtime", "runtime-saveable")
                includeModule("androidx.lifecycle", "lifecycle-common")
                includeModule("androidx.lifecycle", "lifecycle-runtime")
                includeModule("androidx.lifecycle", "lifecycle-runtime-compose")
                includeModule("androidx.lifecycle", "lifecycle-viewmodel")
                includeModule("androidx.lifecycle", "lifecycle-viewmodel-compose")
                includeModule("androidx.lifecycle", "lifecycle-viewmodel-savedstate")
                includeModule("androidx.savedstate", "savedstate")
                includeModule("androidx.savedstate", "savedstate-compose")
            }
        }
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-develop/")
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
        mavenCentral()
    }
}

rootProject.name = "coil-published-source-port"
val coilConfig = JsonSlurper().parse(file("port-config.json")) as Map<*, *>
(coilConfig["modules"] as Map<*, *>).keys.forEach { include(":$it") }
