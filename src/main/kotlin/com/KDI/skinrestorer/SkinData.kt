package com.KDI.skinrestorer

data class SkinData(
    val textureUrl: String,
    val model: String? = null,
    val sourceName: String? = null,
    val sourceUrl: String? = null,
    val automaticallyRestored: Boolean = true,
    val textureValue: String? = null,
    val textureSignature: String? = null
)