plugins {
    // Pinned to 1.17.1 deliberately: this is the exact version Firmament uses
    // against 26.1.2. Later builds (1.17.19) reject a project with no mappings
    // configuration, which is fatal here because 26.1.2 publishes none.
    id("net.fabricmc.fabric-loom") version "1.17.1"
    java
}

fun prop(name: String): String = project.property(name) as String

version = prop("mod_version")
group = prop("maven_group")

base { archivesName.set(prop("archives_base_name")) }

repositories {
    // MoulConfig lives here, not on Maven Central.
    maven("https://maven.notenoughupdates.org/releases/") { name = "NotEnoughUpdates" }
    maven("https://maven.terraformersmc.com/releases/") { name = "TerraformersMC" }
    mavenCentral()
}

dependencies {
    // Quoted configuration names on purpose: loom registers these lazily, so
    // the Kotlin DSL has no type-safe accessors for them and
    // `modImplementation(...)` fails to resolve. Firmament does the same.
    "minecraft"("com.mojang:minecraft:${prop("minecraft_version")}")
    // Deliberately no mappings(...) line. Minecraft 26.1.2 publishes no
    // client_mappings in its version manifest and yarn has no 26.1 build --
    // the jar is used as shipped.
    //
    // And note these are plain `implementation`, not `modImplementation`:
    // with an unobfuscated Minecraft there is nothing to remap, so loom no
    // longer registers the mod* configurations at all.
    implementation("net.fabricmc:fabric-loader:${prop("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${prop("fabric_version")}")

    // Jar-in-jar rather than shadow+relocate: crittermod ships MoulConfig this
    // way on this exact Minecraft version, so it is the proven path.
    val moulconfig = "org.notenoughupdates.moulconfig:modern-26.1:${prop("moulconfig_version")}"
    implementation(moulconfig)
    include(moulconfig)

    // MoulConfig is Kotlin, so javac needs the stdlib on the classpath just to
    // resolve its overloads. Compile-only: fabric-language-kotlin supplies it
    // at runtime, which is why the mod depends on that in fabric.mod.json.
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:${prop("kotlin_version")}")

    // Not bundled - it is a standard mod players already have, and fabric.mod.json
    // declares a hard dependency on it. It is on the dev classpath so `runClient`
    // can actually launch; without it the dev client dies at mod resolution while
    // a real instance would have loaded fine.
    implementation("net.fabricmc:fabric-language-kotlin:${prop("fabric_kotlin_version")}")

    // Adds the config button in Mod Menu's list. Not bundled (no include), and
    // declared only as "recommends" in fabric.mod.json, so the mod still loads
    // without it - but it is on the dev classpath so runClient can test it.
    implementation("com.terraformersmc:modmenu:${prop("modmenu_version")}")
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    // The individual shard icons are the source for the atlas, not shipped:
    // the jar only needs shard_atlas.png. Keeping both would double 600 KB for
    // no reason.
    exclude("assets/squidutils/textures/shard/**")

    inputs.property("version", version)
    filesMatching("fabric.mod.json") {
        expand("version" to version)
    }
}
