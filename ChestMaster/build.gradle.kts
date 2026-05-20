import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.Properties

plugins {
    kotlin("jvm") version "2.3.10"
    id("fabric-loom") version "1.14.10"
    id("maven-publish")
}

// ---------------------------------------------------------------------------
// Multi-version support
// Pass -PmcVersion=1.21.10 (or 1.21.11 / 26.1) to target a specific version.
// Defaults to the values already in gradle.properties if not specified.
// ---------------------------------------------------------------------------
val requestedMcVersion = findProperty("mcVersion") as String?
if (requestedMcVersion != null) {
    val versionFile = rootProject.file("versions/$requestedMcVersion.properties")
    if (!versionFile.exists()) {
        throw GradleException("Version profile not found: versions/$requestedMcVersion.properties")
    }
    val versionProps = Properties().apply { versionFile.inputStream().use(::load) }
    versionProps.forEach { k, v ->
        // setProperty makes values available via project.findProperty() AND providers.gradleProperty()
        setProperty(k.toString(), v.toString())
    }
    logger.lifecycle("ChestMaster: loaded version profile '$requestedMcVersion'")
}

fun prop(key: String): String =
    findProperty(key) as String? ?: error("Missing gradle property: $key")

val minecraftVersion  = prop("minecraft_version")
val loaderVersion     = prop("loader_version")
val kotlinLoaderVersion = prop("kotlin_loader_version")
val fabricVersion     = prop("fabric_version")
val rawModVersion     = prop("mod_version").trim()
val effectiveModVersion = if (rawModVersion.contains("+mc")) rawModVersion
                          else "$rawModVersion+mc$minecraftVersion"

version = effectiveModVersion
group   = prop("maven_group")

base {
    archivesName.set("${prop("archives_base_name")}-mc$minecraftVersion")
}

val targetJavaVersion = 21
java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    withSourcesJar()
}

// Version-specific compat sources are included directly in the client source set.
// Files in src/compat/vX_Y_Z/ bridge API differences (e.g. RenderTypes.LINES vs RenderType.LINES).
val compatDir = "src/compat/v${minecraftVersion.replace(".", "_")}"

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

// Add compat sources to the client source set after loom has set it up.
afterEvaluate {
    val clientSourceSet = sourceSets.findByName("client")
    if (clientSourceSet != null) {
        clientSourceSet.kotlin.srcDir("$rootDir/$compatDir/kotlin")
        logger.lifecycle("ChestMaster: compat sources from '$compatDir' added to client sourceSet")
    } else {
        logger.warn("ChestMaster: 'client' sourceSet not found — compat sources not added")
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
            "version"            to project.version,
            "minecraft_version"  to minecraftVersion,
            "loader_version"     to loaderVersion,
            "kotlin_loader_version" to kotlinLoaderVersion
        )
    )
    filteringCharset = "UTF-8"
    filesMatching("fabric.mod.json") {
        expand(
            "version"               to project.version,
            "minecraft_version"     to minecraftVersion,
            "loader_version"        to loaderVersion,
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

// Convenience task: shows how to build each supported version.
tasks.register("buildAll") {
    group = "build"
    description = "Print instructions to build JARs for all Minecraft version profiles"
    doLast {
        rootProject.file("versions").listFiles()
            ?.filter { it.extension == "properties" }
            ?.sortedBy { it.nameWithoutExtension }
            ?.forEach { f ->
                logger.lifecycle("  ./gradlew build -PmcVersion=${f.nameWithoutExtension}")
            }
    }
}

tasks.register("printBuildTarget") {
    group = "help"
    description = "Print active Minecraft/Fabric target versions"
    doLast {
        println("ChestMaster target -> minecraft=$minecraftVersion  fabric-api=$fabricVersion  loader=$loaderVersion  kotlin-loader=$kotlinLoaderVersion  mod-version=$effectiveModVersion")
    }
}
