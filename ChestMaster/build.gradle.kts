import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.ByteArrayOutputStream
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// MC 26.x is unobfuscated — uses net.fabricmc.fabric-loom (no-remap variant).
plugins {
    kotlin("jvm") version "2.3.10"
    id("net.fabricmc.fabric-loom") version "1.16.2"
    id("maven-publish")
}

// ---------------------------------------------------------------------------
// Multi-version support
// Pass -PmcVersion=26.1.2 (or 26.2) to target a version.
// Defaults to the values already in gradle.properties if not specified.
// ---------------------------------------------------------------------------
val requestedMcVersion = findProperty("mcVersion") as String?
if (requestedMcVersion != null) {
    val versionFile = rootProject.file("versions/$requestedMcVersion.properties")
    if (!versionFile.exists()) {
        throw GradleException("Version profile not found: versions/$requestedMcVersion.properties")
    }
    val versionProps = Properties().apply { versionFile.inputStream().use(::load) }
    versionProps.forEach { k, v -> setProperty(k.toString(), v.toString()) }
    logger.lifecycle("ChestMaster: loaded version profile '$requestedMcVersion'")
}

fun prop(key: String): String =
    findProperty(key) as String? ?: error("Missing gradle property: $key")

val minecraftVersion    = prop("minecraft_version")
val loaderVersion       = prop("loader_version")
val kotlinLoaderVersion = prop("kotlin_loader_version")
val fabricVersion       = prop("fabric_version")
val rawModVersion       = prop("mod_version").trim()
val effectiveModVersion = if (rawModVersion.contains("+mc")) rawModVersion
                          else "$rawModVersion+mc$minecraftVersion"

val targetJavaVersion = 25

// The dep constraint covers the whole patch series without allowing the next
// minor version (e.g. "26.1.2" → ">=26.1 <26.2", "26.2" → ">=26.2 <26.3").
val mcDepVersion = minecraftVersion.split(".").let { parts ->
    val major = parts[0]
    val minor = parts[1].toInt()
    ">=$major.$minor <$major.${minor + 1}"
}

version = effectiveModVersion
group   = prop("maven_group")

base {
    archivesName.set("${prop("archives_base_name")}-mc$minecraftVersion")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    withSourcesJar()
}

// Version-specific compat sources (e.g. src/compat/v26_1_2/, src/compat/v26_2/)
val compatDir = "src/compat/v${minecraftVersion.replace(".", "_")}"

loom {
    splitEnvironmentSourceSets()
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

afterEvaluate {
    val clientSourceSet = sourceSets.findByName("client")
    if (clientSourceSet != null) {
        clientSourceSet.kotlin.srcDir("$rootDir/$compatDir/kotlin")
        logger.lifecycle("ChestMaster: compat sources from '$compatDir' added to client sourceSet")
    } else {
        logger.warn("ChestMaster: 'client' sourceSet not found — compat sources not added")
    }
}

repositories {
    mavenLocal()
    mavenCentral()
    maven { url = uri("https://maven.fabricmc.net/") }
    maven { url = uri("https://maven.terraformersmc.com/releases/") }
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    // No mappings() — MC 26.x is already unobfuscated.

    // Plain implementation — no remapping needed.
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc:fabric-language-kotlin:$kotlinLoaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricVersion")

    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
    include("org.xerial:sqlite-jdbc:3.45.1.0")

    // Mod Menu: compile against the API only; not bundled in the jar.
    compileOnly("com.terraformersmc:modmenu:${prop("modmenu_version")}")
}

tasks.processResources {
    inputs.properties(
        mapOf(
            "version"               to project.version,
            "minecraft_version"     to minecraftVersion,
            "minecraft_dep_version" to mcDepVersion,
            "loader_version"        to loaderVersion,
            "kotlin_loader_version" to kotlinLoaderVersion
        )
    )
    filteringCharset = "UTF-8"
    filesMatching("fabric.mod.json") {
        expand(
            "version"               to project.version,
            "minecraft_version"     to minecraftVersion,
            "minecraft_dep_version" to mcDepVersion,
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

// ---------------------------------------------------------------------------
// Slim the bundled sqlite-jdbc: drop native builds for platforms Minecraft
// never runs on (Android, FreeBSD, Musl, 32-bit, ppc64). Keeps Windows,
// macOS and Linux on x86_64 + aarch64 — ~3.4 MB of natives instead of ~12.9 MB.
// ---------------------------------------------------------------------------
val keptSqliteNativePrefixes = listOf(
    "org/sqlite/native/Windows/x86_64/",
    "org/sqlite/native/Windows/aarch64/",
    "org/sqlite/native/Mac/x86_64/",
    "org/sqlite/native/Mac/aarch64/",
    "org/sqlite/native/Linux/x86_64/",
    "org/sqlite/native/Linux/aarch64/"
)

fun keepSqliteEntry(name: String): Boolean {
    if (!name.startsWith("org/sqlite/native/")) return true
    if (name.endsWith("/")) return keptSqliteNativePrefixes.any { it.startsWith(name) || name.startsWith(it) }
    return keptSqliteNativePrefixes.any { name.startsWith(it) }
}

fun slimInnerSqliteJar(bytes: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    ZipInputStream(bytes.inputStream()).use { zin ->
        ZipOutputStream(out).use { zout ->
            var entry = zin.nextEntry
            while (entry != null) {
                if (keepSqliteEntry(entry.name)) {
                    zout.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
                    zin.copyTo(zout)
                    zout.closeEntry()
                }
                entry = zin.nextEntry
            }
        }
    }
    return out.toByteArray()
}

fun slimNestedSqliteJar(modJar: File) {
    val tmp = File(modJar.parentFile, modJar.name + ".slim-tmp")
    ZipFile(modJar).use { zip ->
        ZipOutputStream(tmp.outputStream().buffered()).use { zout ->
            for (entry in zip.entries()) {
                zout.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
                if (!entry.isDirectory) {
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    val isNestedSqlite = entry.name.startsWith("META-INF/jars/sqlite-jdbc") &&
                        entry.name.endsWith(".jar")
                    zout.write(if (isNestedSqlite) slimInnerSqliteJar(bytes) else bytes)
                }
                zout.closeEntry()
            }
        }
    }
    if (!modJar.delete()) throw GradleException("Could not replace $modJar")
    if (!tmp.renameTo(modJar)) throw GradleException("Could not rename $tmp to $modJar")
}

tasks.named<org.gradle.api.tasks.bundling.Jar>("jar") {
    doLast {
        val jarFile = archiveFile.get().asFile
        slimNestedSqliteJar(jarFile)
        logger.lifecycle(
            "ChestMaster: slimmed bundled sqlite-jdbc natives in ${jarFile.name} " +
                "(now ${"%.1f".format(jarFile.length() / 1024.0 / 1024.0)} MB)"
        )
    }
}

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
        println("ChestMaster target -> minecraft=$minecraftVersion  fabric-api=$fabricVersion  loader=$loaderVersion  kotlin-loader=$kotlinLoaderVersion  mod-version=$effectiveModVersion  java=$targetJavaVersion")
    }
}
