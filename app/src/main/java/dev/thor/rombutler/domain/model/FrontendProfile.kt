package dev.thor.rombutler.domain.model

/**
 * Folder-name presets for popular frontends. Applying a profile replaces
 * all per-system folder overrides in one step; `ESDE` clears them (the
 * built-in defaults already follow the ES-DE convention).
 *
 * Only systems whose names differ from ES-DE are listed; unmapped systems
 * keep their default folder. Sources: Batocera wiki system pages and the
 * Onion "Rom folders" reference (folder names there are case-sensitive).
 */
enum class FrontendProfile(
    val id: String,
    val overrides: Map<String, String>,
) {
    /** ES-DE / EmulationStation-DE — the built-in defaults. */
    ESDE("esde", emptyMap()),

    /** Batocera / Knulli (`/userdata/roms/...`). */
    BATOCERA(
        "batocera",
        mapOf(
            "gc" to "gamecube",
            "n3ds" to "3ds",
            "atarilynx" to "lynx",
            "wonderswan" to "wswan",
            "wonderswancolor" to "wswanc",
            "arcade" to "mame",
            "amiga" to "amiga500",
        ),
    ),

    /** Onion OS on Miyoo handhelds (`Roms/...`, case-sensitive). */
    ONION(
        "onion",
        mapOf(
            "nes" to "FC",
            "snes" to "SFC",
            "gb" to "GB",
            "gbc" to "GBC",
            "gba" to "GBA",
            "nds" to "NDS",
            "psx" to "PS",
            "megadrive" to "MD",
            "mastersystem" to "MS",
            "gamegear" to "GG",
            "sega32x" to "THIRTYTWOX",
            "atari2600" to "ATARI",
            "atari7800" to "SEVENTYEIGHTHUNDRED",
            "atarilynx" to "LYNX",
            "pcengine" to "PCE",
            "ngp" to "NGP",
            "ngpc" to "NGP",
            "wonderswan" to "WS",
            "wonderswancolor" to "WS",
            "amiga" to "AMIGA",
            "c64" to "COMMODORE",
            "arcade" to "ARCADE",
            "neogeo" to "NEOGEO",
        ),
    ),
}
