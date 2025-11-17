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
        val normalizedUrl = normalized.normalizedRtspUrl()
        val components = normalizedUrl.takeIf { it.isNotBlank() }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?.let { uri -> uri.toLegacyComponents() }
        array.put(
            JSONObject().apply {
                put("title", config.title)
                put("url", normalizedUrl)
                put("rtspUrl", normalizedUrl)
                val username = components?.username.orEmpty()
                val password = components?.password.orEmpty()
                val host = components?.host.orEmpty()
                val portString = components?.port ?: DEFAULT_PORT
                val slug = components?.slug ?: DEFAULT_CAMERA_SLUG
                val channel = components?.channel.orEmpty()
                val subtype = components?.subtype.orEmpty()
                put("username", username)
                put("user", username)
                put("password", password)
                put("pass", password)
                put("host", host)
                put("ip", host)
                put("portString", portString)
                put("port", portString.toIntOrNull() ?: DEFAULT_PORT.toInt())
                put("slug", slug)
                put("channel", channel)
                put("subtype", subtype)
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
                        rtspUrl = obj.optStringCompat("url", "rtspUrl").ifBlank {
                            buildRtspUrlFromLegacyFields(
                                username = obj.optStringCompat("username", "user"),
                                password = obj.optStringCompat("password", "pass"),
                                host = obj.optStringCompat("host", "ip"),
                                port = obj.optStringCompat("portString", "port", fallback = DEFAULT_PORT),
                                slug = obj.optString("slug", DEFAULT_CAMERA_SLUG),
                                channel = obj.optString("channel", DEFAULT_CHANNEL),
                                subtype = obj.optString("subtype", DEFAULT_SUBTYPE)
                            )
                        },
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

private data class LegacyRtspComponents(
    val username: String,
    val password: String,
    val host: String,
    val port: String,
    val slug: String,
    val channel: String,
    val subtype: String
)

private fun Uri.toLegacyComponents(): LegacyRtspComponents {
    val authority = encodedAuthority.orEmpty()
    val atIndex = authority.lastIndexOf('@')
    val userInfo = if (atIndex != -1) authority.substring(0, atIndex) else ""
    val (username, password) = decodeUserInfo(userInfo)
    val hostValue = host.orEmpty()
    val portValue = if (port != -1) port.toString() else DEFAULT_PORT
    val slugValue = path?.takeIf { it.isNotEmpty() } ?: DEFAULT_CAMERA_SLUG
    val channelValue = getQueryParameter("channel") ?: ""
    val subtypeValue = getQueryParameter("subtype") ?: ""
    return LegacyRtspComponents(
        username = username,
        password = password,
        host = hostValue,
        port = portValue,
        slug = slugValue,
        channel = channelValue,
        subtype = subtypeValue
    )
}

private fun decodeUserInfo(userInfo: String): Pair<String, String> {
    if (userInfo.isEmpty()) return "" to ""
    val colonIndex = userInfo.indexOf(':')
    return if (colonIndex == -1) {
        Uri.decode(userInfo) to ""
    } else {
        val username = Uri.decode(userInfo.substring(0, colonIndex))
        val password = Uri.decode(userInfo.substring(colonIndex + 1))
        username to password
    }
}

private fun buildRtspUrlFromLegacyFields(
    username: String,
    password: String,
    host: String,
    port: String,
    slug: String,
    channel: String,
    subtype: String
): String {
    if (host.isBlank()) return ""
    val sanitizedPort = port.trim().ifEmpty { DEFAULT_PORT }
    val normalizedPort = sanitizedPort.toIntOrNull()?.toString() ?: DEFAULT_PORT
    val credential = buildString {
        val trimmedUser = username.trim()
        if (trimmedUser.isNotEmpty()) {
            append(Uri.encode(trimmedUser))
            val trimmedPassword = password.trim()
            if (trimmedPassword.isNotEmpty()) {
                append(":")
                append(Uri.encode(trimmedPassword))
            }
            append("@")
        }
    }
    val normalizedHost = host.trim()
    val authorityHost = if (":" in normalizedHost && !normalizedHost.startsWith("[")) {
        "[$normalizedHost]"
    } else {
        normalizedHost
    }
    val authority = buildString {
        append(credential)
        append(authorityHost)
        if (normalizedPort.isNotEmpty()) {
            append(":")
            append(normalizedPort)
        }
    }
    val trimmedSlug = slug.trim()
    val slugUri = if (trimmedSlug.isNotEmpty()) {
        val normalizedSlug = if (trimmedSlug.startsWith("/")) trimmedSlug else "/$trimmedSlug"
        Uri.parse("rtsp://placeholder$normalizedSlug")
    } else {
        null
    }
    val existingQueryParams = slugUri?.queryParameterNames.orEmpty()

    val builder = Uri.Builder()
        .scheme("rtsp")
        .encodedAuthority(authority)
        .apply {
            slugUri?.path?.takeIf { it.isNotEmpty() }?.let { encodedPath(it) }
            slugUri?.queryParameterNames?.forEach { name ->
                slugUri.getQueryParameters(name).forEach { value ->
                    appendQueryParameter(name, value)
                }
            }
            if (channel.isNotBlank() && "channel" !in existingQueryParams) {
                appendQueryParameter("channel", channel)
            }
            if (subtype.isNotBlank() && "subtype" !in existingQueryParams) {
                appendQueryParameter("subtype", subtype)
            }
        }

    return builder.build().toString()
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
