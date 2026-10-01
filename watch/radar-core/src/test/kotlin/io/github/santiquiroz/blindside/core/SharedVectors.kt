package io.github.santiquiroz.blindside.core

import org.json.JSONObject
import java.io.File

object SharedVectors {
    val json: JSONObject by lazy { JSONObject(File(vectorsPath()).readText()) }

    fun hex(key: String): ByteArray = hexToBytes(json.getJSONObject(key).getString("hex"))

    fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun vectorsPath(): String =
        System.getProperty("blindside.vectors") ?: error("blindside.vectors system property is not set")
}
