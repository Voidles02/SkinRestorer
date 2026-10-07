package com.kodari.skinrestorer

data class SkinData(
    val textureUrl: String,
    val model: String? = null,
    val sourceName: String? = null,
    val sourceUrl: String? = null,
    val automaticallyRestored: Boolean = false
)