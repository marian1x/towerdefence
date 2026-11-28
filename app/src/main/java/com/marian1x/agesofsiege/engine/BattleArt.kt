package com.marian1x.agesofsiege.engine

import com.marian1x.agesofsiege.core.Tile

/** Procedural pieces for campaign battlefields and the new raider types. */
internal object BattleArt {
    private const val CLOTH = 0x8E3B2E
    private const val CLOTH_DARK = 0x5E2620
    private const val IRON = 0x6F737B
    private const val SHAMAN_GLOW = 0x7CFF9A

    /** Glider wings carried by Sky Raiders (centered on the rider, wings along X). */
    fun wings(b: MeshBuilder) = with(b) {
        box(-0.03f, 0.55f, -0.18f, 0.03f, 0.6f, 0.2f, Palette.WOOD_DARK)
        for (side in listOf(-1f, 1f)) {
            val x0 = 0.03f * side
            val x1 = 0.72f * side
            tri(x0, 0.62f, -0.18f, x1, 0.7f, 0.05f, x0, 0.62f, 0.22f, CLOTH)
            tri(x0, 0.6f, 0.22f, x1, 0.68f, 0.05f, x0, 0.6f, -0.18f, CLOTH_DARK)
            box(minOf(x0, x1), 0.66f, 0.03f, maxOf(x0, x1), 0.69f, 0.07f, Palette.WOOD)
        }
    }

    /** A wheeled battering ram with a roof; pushed by its crew. */
    fun ram(b: MeshBuilder) = with(b) {
        // Wheels.
        for (x in listOf(-0.32f, 0.32f)) for (z in listOf(-0.32f, 0.3f)) push {
            translate(x, 0.16f, z)
            rotateZ(MeshBuilder.TAU / 4f)
            prism(8, 0.16f, 0.16f, -0.05f, 0.05f, Palette.WOOD_DARK)
        }
        box(-0.3f, 0.16f, -0.5f, 0.3f, 0.24f, 0.5f, Palette.WOOD)
        // The log with an iron head.
        push {
            translate(0f, 0.42f, 0f)
            rotateX(MeshBuilder.TAU / 4f)
            prism(8, 0.11f, 0.11f, -0.55f, 0.55f, 0x8A5A32)
            prism(8, 0.14f, 0.12f, 0.55f, 0.72f, IRON)
        }
        // Roof frame.
        for (x in listOf(-0.27f, 0.27f)) for (z in listOf(-0.42f, 0.42f)) box(x - 0.03f, 0.24f, z - 0.03f, x + 0.03f, 0.75f, z + 0.03f, Palette.WOOD_DARK)
        push { translate(0f, 0.75f, 0f); gable(0.7f, 1.0f, 0f, 0.28f, 0x7A6A52) }
    }

    /** Big tower shield held in front of a Shieldbearer. */
    fun shield(b: MeshBuilder) = with(b) {
        box(-0.2f, 0.12f, 0.2f, 0.2f, 0.78f, 0.25f, 0x7A4A2A, 0x8A5A32)
        box(-0.21f, 0.12f, 0.24f, 0.21f, 0.16f, 0.27f, IRON)
        box(-0.21f, 0.74f, 0.24f, 0.21f, 0.78f, 0.27f, IRON)
        box(-0.05f, 0.35f, 0.25f, 0.05f, 0.55f, 0.29f, Palette.GOLD)
    }

    /** Shaman's staff with a glowing orb. */
    fun staff(b: MeshBuilder) = with(b) {
        box(0.2f, 0f, 0.05f, 0.24f, 1.05f, 0.09f, Palette.WOOD_DARK)
        push { translate(0.22f, 1.12f, 0.07f); ico(0.085f, SHAMAN_GLOW, 1) }
    }

    /** Dirt roads with a stone curb, one draw call for the whole battlefield. */
    fun roads(b: MeshBuilder, tiles: Array<Tile>, w: Int, h: Int) = with(b) {
        fun road(x: Int, y: Int) = x in 0 until w && y in 0 until h && tiles[y * w + x] == Tile.ROAD
        for (y in 0 until h) for (x in 0 until w) {
            if (!road(x, y)) continue
            val n = ((x * 73856093) xor (y * 19349663)) and 0xff
            val c = MeshBuilder.mix(Palette.DIRT, Palette.DIRT_DARK, (n % 40) / 100f)
            box(x + 0f, 0.004f, y + 0f, x + 1f, 0.028f, y + 1f, c)
            // Pebbles and ruts.
            if (n % 3 == 0) box(x + 0.2f, 0.028f, y + 0.3f, x + 0.32f, 0.045f, y + 0.4f, Palette.STONE)
            if (n % 5 == 1) box(x + 0.62f, 0.028f, y + 0.55f, x + 0.7f, 0.04f, y + 0.63f, Palette.STONE_DARK)
            // Curb stones on edges that border grass.
            val curb = Palette.STONE_LIGHT
            if (!road(x - 1, y) && x > 0) box(x + 0f, 0.004f, y + 0.05f, x + 0.07f, 0.06f, y + 0.95f, curb)
            if (!road(x + 1, y) && x < w - 1) box(x + 0.93f, 0.004f, y + 0.05f, x + 1f, 0.06f, y + 0.95f, curb)
            if (!road(x, y - 1) && y > 0) box(x + 0.05f, 0.004f, y + 0f, x + 0.95f, 0.06f, y + 0.07f, curb)
            if (!road(x, y + 1) && y < h - 1) box(x + 0.05f, 0.004f, y + 0.93f, x + 0.95f, 0.06f, y + 1f, curb)
        }
    }

    /** Flat quads for pond tiles (drawn with the water material). */
    fun water(b: MeshBuilder, tiles: Array<Tile>, w: Int, h: Int) = with(b) {
        for (y in 0 until h) for (x in 0 until w) {
            if (tiles[y * w + x] != Tile.WATER) continue
            quad(V3(x + 0f, 0f, y + 1f), V3(x + 1f, 0f, y + 1f), V3(x + 1f, 0f, y + 0f), V3(x + 0f, 0f, y + 0f), Palette.WHITE)
        }
    }

    /** Muddy banks around ponds. */
    fun shores(b: MeshBuilder, tiles: Array<Tile>, w: Int, h: Int) = with(b) {
        for (y in 0 until h) for (x in 0 until w) {
            if (tiles[y * w + x] != Tile.WATER) continue
            box(x - 0.04f, 0.002f, y - 0.04f, x + 1.04f, 0.012f, y + 1.04f, 0x6B5638)
        }
    }

    /** War Banner tower: a stone plinth, a tall pole and a big flag (more flags per level). */
    fun banner(b: MeshBuilder, tier: Int) = with(b) {
        prism(8, 0.42f, 0.38f, 0f, 0.18f, Palette.STONE_DARK, Palette.STONE_LIGHT)
        prism(8, 0.26f, 0.24f, 0.18f, 0.36f, Palette.STONE)
        cbox(0.07f, 0.07f, 0.36f, 1.95f, Palette.WOOD_DARK)
        push { translate(0f, 1.98f, 0f); ico(0.07f, Palette.GOLD, 1) }
        box(0.035f, 1.25f, -0.02f, 0.62f, 1.85f, 0.02f, Palette.ROOF_RED)
        box(0.035f, 1.25f, -0.025f, 0.62f, 1.31f, 0.025f, Palette.GOLD)
        box(0.25f, 1.47f, -0.026f, 0.42f, 1.64f, 0.026f, Palette.GOLD)
        if (tier >= 2) for (side in listOf(-1f, 1f)) push {
            translate(side * 0.3f, 0.18f, 0.2f)
            cbox(0.04f, 0.04f, 0f, 1.0f, Palette.WOOD_DARK)
            box(0.02f, 0.62f, -0.012f, 0.26f, 0.95f, 0.012f, Palette.ROOF_BLUE)
        }
        if (tier >= 3) push { translate(0f, 0.36f, 0f); prism(8, 0.2f, 0.16f, 0f, 0.12f, Palette.GOLD) }
    }

    /** Thin gilded crown for veteran tower types (cosmetic, earned by kills over the campaign). */
    fun gildedTrim(b: MeshBuilder, tier: Int) = with(b) {
        val c = if (tier >= 2) 0xFFE08A else Palette.GOLD
        prism(8, 0.47f, 0.47f, 0.02f, 0.07f, c)
        if (tier >= 2) for (i in 0 until 4) push {
            rotateY(i * MeshBuilder.TAU / 4f + MeshBuilder.TAU / 8f)
            translate(0.44f, 0f, 0f)
            cbox(0.06f, 0.06f, 0.02f, 0.16f, c)
        }
    }
}
