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
        array.put(
            JSONObject().apply {
                put("title", config.title)
                put("username", config.username)
                put("password", config.password)
                put("host", config.host)
                put("port", config.port)
                put("slug", config.slug)
                put("channel", config.channel)
                put("subtype", config.subtype)
                put("transport", config.transport.name)
                put("latencyMs", config.latencyMs)
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
                        username = obj.optString("username"),
                        password = obj.optString("password"),
                        host = obj.optString("host"),
                        port = obj.optString("port", DEFAULT_PORT),
                        slug = obj.optString("slug", DEFAULT_CAMERA_SLUG),
                        channel = obj.optString("channel", DEFAULT_CHANNEL),
                        subtype = obj.optString("subtype", DEFAULT_SUBTYPE),
                        transport = obj.optString("transport").let { stored ->
                            RtspTransport.entries.firstOrNull { it.name == stored } ?: RtspTransport.TCP
                        },
                        latencyMs = obj.optInt("latencyMs", DEFAULT_LATENCY_MS)
                    )
                )
            }
        }
    } catch (_: JSONException) {
        null
    }
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
