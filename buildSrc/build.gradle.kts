plugins {
    id("groovy-gradle-plugin")
    id("java-gradle-plugin")
    kotlin("jvm") version "1.9.20"
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
    maven("https://maven.minecraftforge.net/") {
        name = "MinecraftForge"
    }
    maven("https://maven.architectury.dev/") {
        name = "Architectury"
    }
    maven("https://repo.spongepowered.org/repository/maven-public")
    maven("https://maven.parchmentmc.org")
}

gradlePlugin {
    plugins {
        create("platformPlugin") {
            id = "flywheel.platform"
            implementationClass = "com.jozufozu.gradle.PlatformPlugin"
        }
        create("jarSetPlugin") {
            id = "flywheel.jar-sets"
            implementationClass = "com.jozufozu.gradle.JarSetPlugin"
        }
        create("packageInfosPlugin") {
            id = "flywheel.package-infos"
            implementationClass = "com.jozufozu.gradle.PackageInfosPlugin"
        }
        create("transitiveSourceSetsPlugin") {
            id = "flywheel.transitive-source-sets"
            implementationClass = "com.jozufozu.gradle.TransitiveSourceSetsPlugin"
        }
    }
}

dependencies {
    implementation("dev.architectury.loom:dev.architectury.loom.gradle.plugin:1.6-SNAPSHOT")
}