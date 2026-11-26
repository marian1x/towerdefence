package com.marian1x.agesofsiege.engine

import kotlin.math.PI

/** Medieval-era color palette (sRGB). */
object Palette {
    const val GRASS_A = 0x88B65A
    const val GRASS_B = 0x82B055
    const val GRASS_C = 0x95BF63
    const val MEADOW = 0x76A64C
    const val DIRT = 0xB89058
    const val DIRT_DARK = 0x94703F
    const val CLIFF = 0x9C7650
    const val CLIFF_DARK = 0x6B4E33
    const val STONE = 0xA9ABAE
    const val STONE_LIGHT = 0xCCC8BE
    const val STONE_DARK = 0x7F8186
    const val WOOD = 0xA06E40
    const val WOOD_DARK = 0x6E4A2A
    const val ROOF_RED = 0xC4533A
    const val ROOF_BLUE = 0x3F6FB5
    const val LEAF = 0x5AA33E
    const val LEAF_DARK = 0x3F8331
    const val PINE = 0x2F7A45
    const val PINE_LIGHT = 0x3B8F52
    const val SKIN = 0xE8B48A
    const val CLOTH_RED = 0x9A3030
    const val LEATHER = 0x6B4A2E
    const val STEEL = 0xC0C6CE
    const val GOLD = 0xF2C14E
    const val PLASTER = 0xECDFC2
    const val WOLF = 0x80808A
    const val DARK = 0x2E2E38
    const val GREEN_TUNIC = 0x3E7A3A
    const val WHITE = 0xFFFFFF
}

object Models {
    private const val PI_F = PI.toFloat()

    // ------------------------------------------------------------ structures

    fun keep(b: MeshBuilder) = with(b) {
        box(-0.98f, 0f, -0.98f, 0.98f, 0.22f, 0.98f, Palette.STONE_DARK, Palette.STONE)
        box(-0.82f, 0.22f, -0.82f, 0.82f, 0.95f, 0.82f, Palette.STONE, Palette.STONE_LIGHT)
        // Merlons around the curtain wall.
        val n = 6
        for (i in 0 until n) {
            val t = -0.72f + i * (1.44f / (n - 1))
            box(t - 0.08f, 0.95f, 0.72f, t + 0.08f, 1.08f, 0.82f, Palette.STONE_LIGHT)
            box(t - 0.08f, 0.95f, -0.82f, t + 0.08f, 1.08f, -0.72f, Palette.STONE_LIGHT)
            box(0.72f, 0.95f, t - 0.08f, 0.82f, 1.08f, t + 0.08f, Palette.STONE_LIGHT)
            box(-0.82f, 0.95f, t - 0.08f, -0.72f, 1.08f, t + 0.08f, Palette.STONE_LIGHT)
        }
        // Central donjon.
        box(-0.42f, 0.95f, -0.42f, 0.42f, 1.8f, 0.42f, Palette.STONE_LIGHT, Palette.STONE)
        box(-0.1f, 1.25f, 0.42f, 0.1f, 1.45f, 0.44f, Palette.DARK)
        push {
            translate(0f, 1.8f, 0f)
            prism(4, 0.64f, 0f, 0f, 0.72f, Palette.ROOF_BLUE, angle0 = PI_F / 4)
        }
        // Corner towers.
        for (sx in listOf(-0.74f, 0.74f)) for (sz in listOf(-0.74f, 0.74f)) push {
            translate(sx, 0f, sz)
            prism(8, 0.27f, 0.25f, 0.2f, 1.45f, Palette.STONE, Palette.STONE_LIGHT)
            cone(8, 0.33f, 1.45f, 2.05f, Palette.ROOF_BLUE)
        }
        // Gate.
        box(-0.2f, 0.22f, 0.8f, 0.2f, 0.66f, 0.85f, Palette.WOOD_DARK)
        // Flag.
        box(-0.02f, 2.4f, -0.02f, 0.02f, 3.0f, 0.02f, Palette.WOOD_DARK)
        box(0.02f, 2.72f, -0.01f, 0.38f, 2.96f, 0.01f, Palette.ROOF_RED)
    }

    fun archerTowerBase(b: MeshBuilder, level: Int) = with(b) {
        prism(6, 0.43f, 0.39f, 0f, 0.3f, Palette.STONE_DARK, Palette.STONE)
        val shaft = if (level >= 2) Palette.STONE_LIGHT else Palette.WOOD
        prism(6, 0.33f, 0.29f, 0.3f, 1.15f, shaft, shaft)
        if (level >= 2) prism(6, 0.345f, 0.335f, 0.72f, 0.8f, Palette.STONE_DARK)
        box(-0.43f, 1.15f, -0.43f, 0.43f, 1.27f, 0.43f, Palette.WOOD_DARK, Palette.WOOD)
        for (sx in listOf(-0.37f, 0.37f)) for (sz in listOf(-0.37f, 0.37f)) {
            box(sx - 0.035f, 1.27f, sz - 0.035f, sx + 0.035f, 1.62f, sz + 0.035f, Palette.WOOD_DARK)
        }
        val roof = if (level >= 3) Palette.ROOF_BLUE else Palette.ROOF_RED
        cone(6, 0.58f, 1.6f, 2.15f + 0.1f * level, roof, angle0 = PI_F / 6)
        if (level >= 3) ico(0.06f, Palette.GOLD, 0, cy = 2.47f)
    }

    /** Archer standing on the platform (rotates to aim). */
    fun archerTowerHead(b: MeshBuilder, level: Int) = with(b) {
        push {
            translate(0f, 1.27f, 0f)
            prism(5, 0.09f, 0.07f, 0f, 0.21f, Palette.GREEN_TUNIC)
            ico(0.065f, Palette.SKIN, 1, cy = 0.27f)
            cone(5, 0.08f, 0.28f, 0.4f, Palette.GREEN_TUNIC)
            // Bow.
            box(-0.015f, 0.06f, 0.1f, 0.015f, 0.36f, 0.13f, Palette.WOOD_DARK)
            if (level >= 2) box(0.07f, 0.1f, -0.1f, 0.12f, 0.3f, -0.05f, Palette.LEATHER)
        }
    }

    fun ballistaBase(b: MeshBuilder, level: Int) = with(b) {
        box(-0.44f, 0f, -0.44f, 0.44f, 0.32f, 0.44f, Palette.STONE, Palette.STONE_LIGHT)
        if (level >= 2) box(-0.46f, 0.12f, -0.46f, 0.46f, 0.18f, 0.46f, Palette.STONE_DARK)
        if (level >= 3) for (sx in listOf(-0.36f, 0.36f)) for (sz in listOf(-0.36f, 0.36f)) {
            box(sx - 0.07f, 0.32f, sz - 0.07f, sx + 0.07f, 0.44f, sz + 0.07f, Palette.STONE_LIGHT)
        }
    }

    fun ballistaHead(b: MeshBuilder, level: Int) = with(b) {
        push {
            translate(0f, 0.32f, 0f)
            prism(6, 0.1f, 0.1f, 0f, 0.18f, Palette.WOOD_DARK)
            box(-0.06f, 0.18f, -0.36f, 0.06f, 0.28f, 0.36f, Palette.WOOD)
            val arm = 0.42f + 0.05f * level
            box(-arm, 0.2f, 0.24f, arm, 0.26f, 0.31f, Palette.WOOD_DARK)
            box(-0.025f, 0.28f, -0.22f, 0.025f, 0.32f, 0.5f, Palette.STEEL)
            if (level >= 2) box(-0.07f, 0.17f, -0.05f, 0.07f, 0.29f, 0.02f, Palette.STEEL)
        }
    }

    fun catapultBase(b: MeshBuilder, level: Int) = with(b) {
        box(-0.4f, 0.1f, -0.42f, 0.4f, 0.2f, 0.42f, Palette.WOOD_DARK, Palette.WOOD)
        for (sx in listOf(-0.42f, 0.42f)) for (sz in listOf(-0.28f, 0.28f)) push {
            translate(sx, 0.13f, sz)
            rotateZ(PI_F / 2)
            prism(8, 0.13f, 0.13f, -0.05f, 0.05f, Palette.WOOD_DARK)
        }
        if (level >= 2) box(-0.42f, 0.2f, -0.44f, 0.42f, 0.24f, -0.38f, Palette.STEEL)
    }

    fun catapultHead(b: MeshBuilder, level: Int) = with(b) {
        push {
            translate(0f, 0.2f, 0f)
            for (sx in listOf(-0.2f, 0.2f)) box(sx - 0.04f, 0f, -0.06f, sx + 0.04f, 0.45f, 0.06f, Palette.WOOD)
            box(-0.24f, 0.42f, -0.04f, 0.24f, 0.5f, 0.04f, Palette.WOOD_DARK)
            push {
                translate(0f, 0.46f, 0f)
                rotateX(-0.55f)
                box(-0.035f, -0.035f, -0.62f, 0.035f, 0.035f, 0.22f, Palette.WOOD)
                box(-0.1f, -0.02f, -0.72f, 0.1f, 0.08f, -0.55f, Palette.WOOD_DARK)
                box(-0.1f, -0.16f, 0.12f, 0.1f, 0.04f, 0.3f, if (level >= 2) Palette.STEEL else Palette.STONE_DARK)
            }
        }
    }

    fun wall(b: MeshBuilder) = with(b) {
        box(-0.5f, 0f, -0.5f, 0.5f, 0.55f, 0.5f, Palette.STONE, Palette.STONE_LIGHT)
        for (sx in listOf(-0.35f, 0.35f)) for (sz in listOf(-0.35f, 0.35f)) {
            box(sx - 0.1f, 0.55f, sz - 0.1f, sx + 0.1f, 0.7f, sz + 0.1f, Palette.STONE_LIGHT)
        }
    }

    fun house(b: MeshBuilder, level: Int) = with(b) {
        val h = 0.4f + 0.18f * (level - 1)
        box(-0.34f, 0f, -0.3f, 0.34f, h, 0.3f, Palette.PLASTER)
        for (sx in listOf(-0.345f, 0.335f)) box(sx, 0f, -0.31f, sx + 0.01f, h, 0.31f, Palette.WOOD_DARK)
        box(-0.35f, h - 0.04f, -0.31f, 0.35f, h, 0.31f, Palette.WOOD_DARK)
        box(-0.08f, 0f, 0.3f, 0.08f, 0.24f, 0.32f, Palette.WOOD_DARK)
        box(0.15f, 0.2f, 0.3f, 0.27f, 0.3f, 0.315f, 0x8FC3E0)
        push {
            translate(0f, 0f, 0f)
            rotateY(PI_F / 2)
            gable(0.72f, 0.82f, h, 0.32f, Palette.ROOF_RED)
        }
        box(0.16f, h + 0.1f, -0.16f, 0.26f, h + 0.42f, -0.06f, Palette.STONE_DARK)
        if (level >= 3) box(-0.42f, 0f, -0.42f, 0.42f, 0.04f, 0.42f, Palette.STONE)
    }

    fun lumberMill(b: MeshBuilder, level: Int) = with(b) {
        box(-0.36f, 0f, -0.28f, 0.2f, 0.38f, 0.28f, Palette.WOOD, Palette.WOOD)
        push {
            translate(-0.08f, 0f, 0f)
            rotateY(PI_F / 2)
            gable(0.66f, 0.7f, 0.38f, 0.26f, Palette.WOOD_DARK)
        }
        // Log pile.
        for ((i, y) in listOf(0.06f, 0.06f, 0.06f, 0.17f, 0.17f).withIndex()) push {
            val z = if (i < 3) -0.2f + i * 0.13f else -0.13f + (i - 3) * 0.13f
            translate(0.33f, y, z)
            rotateZ(PI_F / 2)
            prism(6, 0.06f, 0.06f, -0.14f, 0.14f, Palette.WOOD_DARK, 0xD9B27A)
        }
        // Saw blade.
        push {
            translate(-0.08f, 0.22f, 0.29f)
            rotateX(PI_F / 2)
            prism(10, 0.12f + 0.02f * level, 0.12f + 0.02f * level, -0.01f, 0.01f, Palette.STEEL)
        }
    }

    fun quarry(b: MeshBuilder, level: Int) = with(b) {
        ico(0.36f, Palette.STONE_DARK, 1, jitter = 0.22f, seed = 7, squashY = 0.6f, cy = 0.05f)
        box(0.12f, 0f, 0.1f, 0.34f, 0.16f, 0.32f, Palette.STONE_LIGHT)
        box(-0.36f, 0f, 0.14f, -0.18f, 0.14f, 0.34f, Palette.STONE_LIGHT)
        if (level >= 2) box(0.16f, 0.16f, 0.14f, 0.3f, 0.28f, 0.28f, Palette.STONE_LIGHT)
        // Crane.
        box(0.3f, 0f, -0.34f, 0.36f, 0.62f, -0.28f, Palette.WOOD)
        box(-0.05f, 0.58f, -0.34f, 0.36f, 0.63f, -0.29f, Palette.WOOD_DARK)
        box(-0.02f, 0.3f, -0.33f, 0.0f, 0.58f, -0.31f, Palette.DARK)
    }

    // ------------------------------------------------------------ nature

    fun pine(b: MeshBuilder, variant: Int) = with(b) {
        val s = 0.85f + (variant % 5) * 0.08f
        push {
            scale(s)
            prism(5, 0.06f, 0.05f, 0f, 0.22f, Palette.WOOD_DARK)
            cone(7, 0.34f, 0.15f, 0.62f, Palette.PINE)
            cone(7, 0.27f, 0.42f, 0.86f, Palette.PINE_LIGHT)
            cone(7, 0.18f, 0.66f, 1.06f, Palette.PINE)
        }
    }

    fun roundTree(b: MeshBuilder, variant: Int) = with(b) {
        val s = 0.85f + (variant % 4) * 0.1f
        push {
            scale(s)
            prism(5, 0.06f, 0.05f, 0f, 0.35f, Palette.WOOD_DARK)
            ico(0.3f, if (variant % 2 == 0) Palette.LEAF else Palette.LEAF_DARK, 1, jitter = 0.12f, seed = variant, cy = 0.58f)
        }
    }

    fun rock(b: MeshBuilder, variant: Int) = with(b) {
        ico(0.3f, Palette.STONE, 1, jitter = 0.25f, seed = variant, squashY = 0.62f, cy = 0.08f)
        if (variant % 2 == 0) push {
            translate(0.22f, 0f, 0.15f)
            ico(0.13f, Palette.STONE_DARK, 0, jitter = 0.2f, seed = variant + 1, squashY = 0.7f, cy = 0.04f)
        }
    }

    fun bush(b: MeshBuilder, variant: Int) = with(b) {
        ico(0.17f, Palette.LEAF_DARK, 1, jitter = 0.15f, seed = variant, squashY = 0.8f, cy = 0.1f)
    }

    // ------------------------------------------------------------ units (face +Z)

    fun raider(b: MeshBuilder) = with(b) {
        for (sx in listOf(-0.055f, 0.055f)) box(sx - 0.035f, 0f, -0.035f, sx + 0.035f, 0.17f, 0.035f, Palette.LEATHER)
        prism(6, 0.13f, 0.11f, 0.15f, 0.42f, Palette.CLOTH_RED)
        prism(6, 0.135f, 0.135f, 0.2f, 0.24f, Palette.LEATHER)
        ico(0.09f, Palette.SKIN, 1, cy = 0.5f)
        prism(6, 0.1f, 0.06f, 0.53f, 0.61f, Palette.STEEL)
        // Sword (right) and round shield (left).
        box(0.13f, 0.22f, 0f, 0.16f, 0.25f, 0.3f, Palette.STEEL)
        push {
            translate(-0.15f, 0.3f, 0.03f)
            rotateZ(PI_F / 2)
            prism(8, 0.12f, 0.12f, -0.02f, 0.02f, Palette.WOOD, Palette.WOOD)
        }
    }

    fun brute(b: MeshBuilder) = with(b) {
        for (sx in listOf(-0.08f, 0.08f)) box(sx - 0.05f, 0f, -0.05f, sx + 0.05f, 0.18f, 0.05f, Palette.LEATHER)
        prism(6, 0.2f, 0.16f, 0.16f, 0.52f, 0x5C3B2A)
        ico(0.1f, Palette.STEEL, 0, cy = 0.5f, squashY = 0.7f)
        ico(0.1f, Palette.SKIN, 1, cy = 0.6f)
        // Horned helmet.
        prism(6, 0.11f, 0.08f, 0.63f, 0.7f, Palette.STEEL)
        for (sx in listOf(-1f, 1f)) push {
            translate(sx * 0.1f, 0.66f, 0f)
            rotateZ(-sx * 0.9f)
            cone(5, 0.035f, 0f, 0.16f, 0xEDE3CF)
        }
        // Great axe.
        box(0.19f, 0.1f, 0.0f, 0.22f, 0.13f, 0.45f, Palette.WOOD_DARK)
        box(0.17f, 0.05f, 0.33f, 0.24f, 0.27f, 0.47f, Palette.STEEL)
    }

    fun wolfRider(b: MeshBuilder) = with(b) {
        for (sx in listOf(-0.08f, 0.08f)) for (sz in listOf(-0.2f, 0.2f)) {
            box(sx - 0.03f, 0f, sz - 0.03f, sx + 0.03f, 0.17f, sz + 0.03f, Palette.WOLF)
        }
        box(-0.11f, 0.15f, -0.28f, 0.11f, 0.33f, 0.28f, Palette.WOLF, 0x9A9AA4)
        box(-0.08f, 0.22f, 0.26f, 0.08f, 0.36f, 0.44f, Palette.WOLF)
        box(-0.045f, 0.22f, 0.43f, 0.045f, 0.29f, 0.53f, 0x606068)
        for (sx in listOf(-0.05f, 0.05f)) push { translate(sx, 0.36f, 0.32f); cone(4, 0.03f, 0f, 0.08f, 0x606068) }
        push { translate(0f, 0.3f, -0.3f); rotateX(-0.8f); box(-0.025f, 0f, -0.025f, 0.025f, 0.2f, 0.025f, Palette.WOLF) }
        // Rider.
        prism(6, 0.1f, 0.08f, 0.33f, 0.55f, Palette.CLOTH_RED)
        ico(0.07f, Palette.SKIN, 1, cy = 0.62f)
        cone(6, 0.08f, 0.64f, 0.76f, Palette.LEATHER)
        box(0.1f, 0.42f, -0.2f, 0.12f, 0.44f, 0.5f, Palette.WOOD_DARK)
        box(0.095f, 0.41f, 0.5f, 0.125f, 0.45f, 0.58f, Palette.STEEL)
    }

    fun warlord(b: MeshBuilder) = with(b) {
        for (sx in listOf(-0.09f, 0.09f)) box(sx - 0.055f, 0f, -0.055f, sx + 0.055f, 0.2f, 0.055f, Palette.DARK)
        prism(8, 0.22f, 0.17f, 0.18f, 0.58f, 0x3A3346)
        box(-0.2f, 0.2f, -0.2f, 0.2f, 0.56f, -0.17f, Palette.CLOTH_RED) // cape
        for (sx in listOf(-0.2f, 0.2f)) push { translate(sx, 0f, 0f); ico(0.085f, Palette.STEEL, 0, cy = 0.55f) }
        ico(0.1f, Palette.SKIN, 1, cy = 0.68f)
        prism(8, 0.11f, 0.1f, 0.72f, 0.8f, Palette.GOLD)
        for (i in 0 until 5) push {
            rotateY(i * MeshBuilder.TAU / 5)
            translate(0.09f, 0.8f, 0f)
            cone(4, 0.025f, 0f, 0.08f, Palette.GOLD)
        }
        box(0.22f, 0.15f, -0.02f, 0.26f, 0.2f, 0.62f, Palette.STEEL)
        box(0.19f, 0.13f, 0.04f, 0.29f, 0.22f, 0.08f, Palette.GOLD)
    }

    // ------------------------------------------------------------ projectiles & effects

    fun arrow(b: MeshBuilder) = with(b) {
        box(-0.015f, -0.015f, -0.2f, 0.015f, 0.015f, 0.18f, Palette.WOOD)
        box(-0.025f, -0.025f, 0.18f, 0.025f, 0.025f, 0.24f, Palette.STEEL)
        box(-0.04f, -0.005f, -0.22f, 0.04f, 0.005f, -0.14f, Palette.WHITE)
    }

    fun bolt(b: MeshBuilder) = with(b) {
        box(-0.03f, -0.03f, -0.32f, 0.03f, 0.03f, 0.28f, Palette.WOOD_DARK)
        box(-0.05f, -0.05f, 0.26f, 0.05f, 0.05f, 0.38f, Palette.STEEL)
    }

    fun boulder(b: MeshBuilder) = with(b) {
        ico(0.14f, Palette.STONE_DARK, 1, jitter = 0.2f, seed = 3)
    }

    fun cube(b: MeshBuilder) = with(b) {
        box(-0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f, Palette.WHITE)
    }

    fun tileMarker(b: MeshBuilder) = with(b) {
        box(-0.48f, 0.01f, -0.48f, 0.48f, 0.05f, 0.48f, Palette.WHITE)
    }

    fun ringMesh(b: MeshBuilder) = with(b) {
        ring(0.96f, 1f, 0.04f, 64, Palette.WHITE)
    }

    fun discMesh(b: MeshBuilder) = with(b) {
        disc(1f, 0.03f, 40, Palette.WHITE)
    }
}
