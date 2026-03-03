import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.3.10"
    id("fabric-loom") version "1.14.10"
    id("maven-publish")
}

val minecraftVersion = providers.gradleProperty("minecraft_version").get()
val loaderVersion = providers.gradleProperty("loader_version").get()
val kotlinLoaderVersion = providers.gradleProperty("kotlin_loader_version").get()
val fabricVersion = providers.gradleProperty("fabric_version").get()
val rawModVersion = providers.gradleProperty("mod_version").get().trim()
val effectiveModVersion = if (rawModVersion.contains("+mc")) rawModVersion else "$rawModVersion+mc$minecraftVersion"

version = effectiveModVersion
group = providers.gradleProperty("maven_group").get()

base {
    archivesName.set(providers.gradleProperty("archives_base_name").get())
}

val targetJavaVersion = 21
java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    withSourcesJar()
}

loom {
    splitEnvironmentSourceSets()
    getEnableModProvidedJavadoc().set(false)
    mods {
        register("chestmaster") {
            sourceSet("main")
            sourceSet("client")
        }
    }
    interfaceInjection {
        getIsEnabled().set(false)
    }
}

fabricApi {
    configureDataGeneration {
        client = true
    }
}

repositories {
    mavenCentral()
    maven { url = uri("https://maven.fabricmc.net/") }
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("net.fabricmc:fabric-language-kotlin:$kotlinLoaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricVersion")

    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
    include("org.xerial:sqlite-jdbc:3.45.1.0")
}

tasks.processResources {
    inputs.properties(
        mapOf(
            "version" to project.version,
            "minecraft_version" to minecraftVersion,
            "loader_version" to loaderVersion,
            "kotlin_loader_version" to kotlinLoaderVersion
        )
    )
    filteringCharset = "UTF-8"
    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "minecraft_version" to minecraftVersion,
            "loader_version" to loaderVersion,
            "kotlin_loader_version" to kotlinLoaderVersion
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(targetJavaVersion)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(targetJavaVersion.toString()))
}

tasks.register("printBuildTarget") {
    group = "help"
    description = "Print active Minecraft/Fabric target versions"
    doLast {
        println("ChestMaster target -> minecraft=$minecraftVersion, fabric-api=$fabricVersion, loader=$loaderVersion, kotlin-loader=$kotlinLoaderVersion, mod-version=$effectiveModVersion")
    }
}
