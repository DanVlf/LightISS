pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            name = "JitPack"
            url = uri("https://jitpack.io")
        }
    }
}

rootProject.name = "LightISS"

includeBuild("plugin")
include(":lint-rules")
include(":sdk:shared")
include(":sdk:ui")
include(":sdk:client")
include(":tool")

// The tool builder passes -DlightSdk.toolOnly=true to configure only what
// :tool needs.
if (providers.systemProperty("lightSdk.toolOnly").orNull != "true") {
    include(":sdk:trust")
    include(":sdk:server")
    include(":sdk:emulator")
}
