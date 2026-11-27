package com.marian1x.agesofsiege.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SavedStructure(
    val defId: String,
    val x: Int,
    val y: Int,
    val level: Int,
    val xp: Int,
    val kills: Int,
    val invested: Cost = Cost(),
    val specialization: TowerSpecialization? = null,
)

/** A soldier's persistent state (at home, or travelling with an expedition). */
@Serializable
data class TroopData(val defId: String, var hp: Float, var xp: Int = 0, var kills: Int = 0)

@Serializable
data class SaveData(
    val version: Int = 1,
    val seed: Long,
    val gold: Float,
    val wood: Float,
    val stone: Float,
    val wave: Int,
    val bestWave: Int = 0,
    val structures: List<SavedStructure>,
    val troops: List<TroopData> = emptyList(),
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun fromJson(text: String): SaveData = json.decodeFromString(serializer(), text)
    }
}
