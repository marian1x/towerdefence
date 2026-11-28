package com.marian1x.agesofsiege.engine

import com.marian1x.agesofsiege.core.Content
import com.marian1x.agesofsiege.core.TowerSpecialization

/** Tile-scale models and upgrade details for the base diorama. */
internal object TowerArt {
    const val ICE = 0x70DCFA
    const val FIRE = 0xFF8A39
    const val BRONZE = 0xB6753E
    const val REPEATER_MOUNT = 0.72f

    private const val BASALT = 0x58657A
    private const val BRASS = 0xD5A14D
    private const val ICE_SHADOW = 0x3299C9
    private const val IVORY = 0xEAF8FF
    private const val GREEN = 0x54806A

    fun hasBody(id: String) = id == "frost" || id == "brazier" || id == "repeater" || id == "banner"

    fun body(builder: MeshBuilder, id: String, tier: Int) = with(builder) {
        when (id) {
            "banner" -> BattleArt.banner(this, tier)
            "frost" -> {
                prism(8, 0.45f, 0.41f, 0f, 0.15f, Palette.STONE_DARK, Palette.STONE_LIGHT)
                prism(6, 0.29f, 0.23f, 0.15f, 0.76f, BASALT, Palette.STONE_LIGHT)
                prism(6, 0.33f, 0.34f, 0.67f, 0.79f, ICE_SHADOW, ICE)
                crystal(this, 0.17f, 0.78f, 1.55f)
                for (i in 0 until (tier + 2)) push {
                    rotateY(i * MeshBuilder.TAU / (tier + 2))
                    translate(0.26f, 0.53f, 0f)
                    rotateZ(-0.32f)
                    crystal(this, 0.075f, 0f, 0.46f)
                }
            }
            "brazier" -> {
                prism(8, 0.44f, 0.37f, 0f, 0.14f, Palette.STONE_DARK, Palette.STONE_LIGHT)
                prism(6, 0.22f, 0.27f, 0.14f, 0.54f, BASALT)
                prism(8, 0.21f, 0.41f, 0.43f, 0.69f, BRONZE, Palette.DARK)
                prism(8, 0.42f, 0.39f, 0.67f, 0.74f, BRASS, 0x42252D)
                ico(0.23f, 0xD94827, 0, squashY = 0.28f, cy = 0.76f)
                for (i in 0..2) push {
                    rotateY(i * MeshBuilder.TAU / 3f)
                    translate(0.13f, 0.75f, 0f)
                    rotateZ(-0.18f - i * 0.08f)
                    cone(5, 0.11f, 0f, 0.23f + 0.06f * i, FIRE)
                }
                cone(6, 0.105f, 0.76f, 1.21f, 0xFFE69C)
                if (tier > 1) prism(8, 0.29f, 0.3f, 0.23f, 0.3f, BRASS)
            }
            "repeater" -> {
                cbox(0.91f, 0.9f, 0f, 0.14f, Palette.STONE_DARK, Palette.STONE_LIGHT)
                cbox(0.65f, 0.64f, 0.14f, 0.6f, BASALT, Palette.STONE)
                cbox(0.82f, 0.8f, 0.59f, REPEATER_MOUNT, Palette.WOOD_DARK, Palette.WOOD)
                for (x in listOf(-0.33f, 0.33f)) {
                    box(x - 0.045f, 0.12f, -0.28f, x + 0.045f, 0.62f, 0.28f, Palette.WOOD)
                }
                if (tier > 1) cbox(0.74f, 0.72f, 0.42f, 0.48f, GREEN)
            }
        }
    }

    fun repeaterHead(builder: MeshBuilder) = with(builder) {
        prism(8, 0.19f, 0.15f, 0f, 0.11f, BRASS)
        cbox(0.26f, 0.5f, 0.1f, 0.26f, Palette.WOOD_DARK, Palette.WOOD)
        cbox(0.23f, 0.31f, 0.25f, 0.38f, GREEN)
        box(-0.32f, 0.16f, 0.18f, 0.32f, 0.22f, 0.26f, Palette.STEEL, IVORY)
        for (x in listOf(-0.082f, 0f, 0.082f)) {
            box(x - 0.016f, 0.226f, 0.03f, x + 0.016f, 0.26f, 0.39f, Palette.STEEL)
        }
    }

    fun upgradeDetail(builder: MeshBuilder, level: Int, specialization: TowerSpecialization?) = with(builder) {
        val color = when (specialization) {
            TowerSpecialization.POWER -> 0xD7584A
            TowerSpecialization.REACH -> 0x49BDD6
            TowerSpecialization.RAPID -> 0x63BD78
            null -> BRASS
        }
        if (level >= 4) {
            prism(8, 0.51f, 0.49f, 0.016f, 0.105f, BASALT, BRASS)
            if (level >= 5) prism(8, 0.52f, 0.50f, 0.108f, 0.145f, BRASS)
        }
        if (specialization != null) {
            for (sign in listOf(-1, 1)) push {
                translate(sign * 0.45f, 0f, 0.31f)
                banner(this, color, 0.82f)
            }
        }
    }

    fun shot(builder: MeshBuilder, visual: String) = with(builder) {
        if (visual == "frost") {
            rotateX(MeshBuilder.TAU / 4f)
            crystal(this, 0.095f, -0.15f, 0.21f)
        } else {
            ico(0.12f, FIRE, 0, squashY = 0.9f)
            ico(0.065f, 0xFFE960, 0, cy = 0.025f)
        }
    }

    /** Entrance flags sit at the island edge, clear of buildable tiles. */
    fun gates(builder: MeshBuilder, gates: List<Pair<Int, Int>>, allOpen: Boolean = false, width: Int = 26, height: Int = 16) = with(builder) {
        for ((index, gate) in gates.withIndex()) {
            val (x, z) = gate
            val color = if (allOpen || index < Content.gatesFor(1)) Palette.ROOF_RED else 0x807DA4
            for (side in listOf(-1f, 1f)) push {
                when {
                    x == 0 -> {
                        translate(0.05f, 0f, z + 0.5f + side * 0.43f)
                        rotateY(MeshBuilder.TAU / 4f)
                    }
                    x == width - 1 -> {
                        translate(width - 0.05f, 0f, z + 0.5f + side * 0.43f)
                        rotateY(-MeshBuilder.TAU / 4f)
                    }
                    z == height - 1 -> {
                        translate(x + 0.5f + side * 0.43f, 0f, height - 0.05f)
                        rotateY(MeshBuilder.TAU / 2f)
                    }
                    else -> translate(x + 0.5f + side * 0.43f, 0f, 0.05f)
                }
                banner(this, color, 1.34f)
                cbox(0.19f, 0.18f, 0f, 0.14f, Palette.STONE_DARK, Palette.STONE_LIGHT)
            }
        }
    }

    private fun crystal(builder: MeshBuilder, radius: Float, low: Float, high: Float) = with(builder) {
        val middle = low + (high - low) * 0.54f
        prism(5, radius * 0.45f, radius, low, middle, ICE_SHADOW)
        cone(5, radius, middle, high, ICE)
    }

    private fun banner(builder: MeshBuilder, color: Int, height: Float) = with(builder) {
        cbox(0.042f, 0.042f, 0f, height, Palette.WOOD_DARK)
        ico(0.045f, BRASS, 0, cy = height)
        box(-0.05f, height - 0.16f, -0.02f, 0.22f, height - 0.12f, 0.02f, BRASS)
        box(0.02f, height - 0.41f, -0.012f, 0.2f, height - 0.15f, 0.012f, color)
        box(0.02f, height - 0.41f, -0.014f, 0.2f, height - 0.38f, 0.014f, BRASS)
    }
}
