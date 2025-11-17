package org.archuser.rtspview

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

internal fun serializeCameraSettings(configs: List<CameraConfig>): String {
    val array = JSONArray()
    configs.take(SLOT_COUNT).forEach { config ->
        val normalized = config.normalized()
        val sanitizedPort = normalized.port.toIntOrNull() ?: DEFAULT_PORT.toInt()
        array.put(
            JSONObject().apply {
                put("title", config.title)
                put("username", config.username)
                put("user", config.username)
                put("password", config.password)
                put("pass", config.password)
                put("host", config.host)
                put("ip", config.host)
                put("portString", config.port)
                put("port", sanitizedPort)
                put("slug", config.slug)
                put("channel", config.channel)
                put("subtype", config.subtype)
                val transportValue = normalized.transport.name.lowercase()
                put("transport", transportValue)
                put("latencyMs", config.latencyMs)
                put("latency", config.latencyMs)
            }
        )
    }
    return array.toString()
}

internal fun parseCameraSettings(json: String?): List<CameraConfig>? {
    if (json.isNullOrBlank()) return null
    return try {
        val array = JSONArray(json)
        buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                add(
                    CameraConfig(
                        title = obj.optString("title"),
                        username = obj.optStringCompat("username", "user"),
                        password = obj.optStringCompat("password", "pass"),
                        host = obj.optStringCompat("host", "ip"),
                        port = obj.optStringCompat("portString", "port", fallback = DEFAULT_PORT),
                        slug = obj.optString("slug", DEFAULT_CAMERA_SLUG),
                        channel = obj.optString("channel", DEFAULT_CHANNEL),
                        subtype = obj.optString("subtype", DEFAULT_SUBTYPE),
                        transport = obj.optString("transport").let { stored ->
                            RtspTransport.entries.firstOrNull {
                                it.name.equals(stored, ignoreCase = true) ||
                                    it.title.equals(stored, ignoreCase = true)
                            } ?: RtspTransport.TCP
                        },
                        latencyMs = obj.optIntCompat(DEFAULT_LATENCY_MS, "latencyMs", "latency")
                    )
                )
            }
        }
    } catch (_: JSONException) {
        null
    }
}

private fun JSONObject.optStringCompat(vararg keys: String, fallback: String = ""): String {
    keys.forEach { key ->
        if (has(key)) {
            val value = opt(key)
            if (value != null && value != JSONObject.NULL) {
                return when (value) {
                    is String -> value
                    is Number -> value.toString()
                    else -> fallback
                }
            }
        }
    }
    return fallback
}

private fun JSONObject.optIntCompat(default: Int, vararg keys: String): Int {
    keys.forEach { key ->
        if (has(key)) {
            val value = opt(key)
            when (value) {
                is Number -> return value.toInt()
                is String -> value.toIntOrNull()?.let { return it }
            }
        }
    }
    return default
}

internal fun applyCameraSettings(target: MutableList<CameraConfig>, configs: List<CameraConfig>) {
    configs.take(SLOT_COUNT).forEachIndexed { index, config ->
        if (index < target.size) {
            target[index] = config
        }
    }
    for (index in configs.size until SLOT_COUNT) {
        if (index < target.size) {
            target[index] = CameraConfig()
        }
    }
}

internal suspend fun exportCameraSettings(
    context: Context,
    uri: Uri,
    configs: List<CameraConfig>
) {
    withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            OutputStreamWriter(stream).use { writer ->
                writer.write(serializeCameraSettings(configs))
            }
        }
    }
}

internal suspend fun importCameraSettings(
    context: Context,
    uri: Uri
): List<CameraConfig>? {
    val json = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BufferedReader(InputStreamReader(stream)).use { reader ->
                reader.readText()
            }
        }
    }
    return parseCameraSettings(json)
}
