pluginManagement {
    repositories {
        // Architectury Loom, not stock Fabric Loom. Minecraft 26.1.2 ships
        // without obfuscation mappings and has no yarn or intermediary build,
        // which stock loom refuses to set up ("Configuration 'mappings' has no
        // dependencies"). Architectury's fork handles it, and it is what
        // Firmament -- the reference 26.1.2 SkyBlock mod -- uses.
        maven("https://maven.architectury.dev/") { name = "Architectury" }
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        // Loom pulls mcinjector and DiffPatch from Forge's maven.
        maven("https://maven.minecraftforge.net/") { name = "Forge" }
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "shardfuse"
