import java.util.Properties

plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.3.0"
}

val pluginGroup: String by project
val pluginVersion: String by project
val pluginSinceBuild: String by project

val localProperties = Properties().apply {
    val file = rootDir.resolve("local.properties")
    if (file.isFile) {
        file.inputStream().use(::load)
    }
}
val studioPath = providers.gradleProperty("studioPath")
    .orElse(providers.environmentVariable("APP_PURGE_STUDIO_PATH"))
    .orElse(provider { localProperties.getProperty("studioPath").orEmpty() })
    .map(String::trim)
    .orNull
    ?.takeIf(String::isNotEmpty)
    ?: error("Missing studioPath. Set APP_PURGE_STUDIO_PATH or local.properties.")

group = pluginGroup
version = pluginVersion

repositories {
    mavenCentral()
    intellijPlatform {
        localPlatformArtifacts()
        intellijDependencies()
    }
}

dependencies {
    intellijPlatform {
        local(studioPath)
        bundledPlugin("org.jetbrains.android")
        bundledPlugin("com.intellij.java")
    }
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = pluginSinceBuild
        }
    }
}

tasks {
    patchPluginXml {
        sinceBuild = pluginSinceBuild
    }

    // Delete old zips before every build
    named("buildPlugin") {
        doFirst {
            fileTree("build/distributions").matching { include("*.zip") }.forEach { it.delete() }
        }
    }
}
