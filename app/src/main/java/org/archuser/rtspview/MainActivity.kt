@file:Suppress("SpellCheckingInspection")

package org.archuser.rtspview

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.consumePositionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import androidx.media3.common.util.UnstableApi
import androidx.core.content.edit
import androidx.core.net.toUri
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.archuser.rtspview.ui.theme.RTSPViewTheme
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.roundToInt

@UnstableApi
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RTSPViewTheme {
                RtspViewerApp()
            }
        }
    }
}

internal const val DEFAULT_CAMERA_SLUG = "/cam/realmonitor"
internal const val DEFAULT_PORT = "554"
internal const val DEFAULT_CHANNEL = "1"
internal const val DEFAULT_SUBTYPE = "0"
internal const val DEFAULT_LATENCY_MS = 100
internal const val SLOT_COUNT = 16
private const val HIDE_CONTROLS_DELAY_MS = 5_000L
private const val DRAG_THRESHOLD = 120f
private const val PREFS_NAME = "camera_settings"
private const val PREFS_KEY_SLOTS = "slots"
private const val PREFS_KEY_LOGS = "logs"
private const val EXPORT_FILE_NAME = "rtsp_cameras.json"

private enum class AppScreen { Player, Settings, Logs }

internal enum class RtspTransport(val title: String) {
    TCP("TCP"),
    UDP("UDP")
}

private data class LogEntry(val timestamp: Long, val message: String)

internal data class CameraConfig(
    val title: String = "",
    val fullUrl: String = "",
    val username: String = "",
    val password: String = "",
    val host: String = "",
    val port: String = DEFAULT_PORT,
    val slug: String = DEFAULT_CAMERA_SLUG,
    val channel: String = DEFAULT_CHANNEL,
    val subtype: String = DEFAULT_SUBTYPE,
    val transport: RtspTransport = RtspTransport.TCP,
    val latencyMs: Int = DEFAULT_LATENCY_MS
) {
    fun normalized(): CameraConfig = copy(
        title = title.trim(),
        fullUrl = fullUrl.trim(),
        username = username.trim(),
        password = password.trim(),
        host = host.trim(),
        port = port.trim().ifEmpty { DEFAULT_PORT },
        slug = slug.trim().ifEmpty { DEFAULT_CAMERA_SLUG },
        channel = channel.trim(),
        subtype = subtype.trim(),
        latencyMs = latencyMs.coerceIn(0, 2_000)
    )

    fun validationError(): String? {
        if (fullUrl.isNotBlank() && !fullUrl.lowercase().startsWith("rtsp://")) {
            return "RTSP URL must start with rtsp://"
        }
        if (fullUrl.isBlank() && host.isBlank()) return "RTSP URL is required"
        return null
    }

    fun displayName(): String =
        title.ifBlank {
            when {
                fullUrl.isNotBlank() -> fullUrl
                host.isNotBlank() -> host
                else -> "RTSP Stream"
            }
        }

    fun toRtspUri(includePassword: Boolean = false): Uri {
        val normalized = normalized()
        if (normalized.fullUrl.isNotBlank()) {
            val parsed = normalized.fullUrl.toUri()
            val sanitizedAuthority = encodedAuthorityWithEncodedCredentials(normalized.fullUrl, includePassword)
                ?: parsed.encodedAuthority?.let { authority ->
                    if (includePassword) authority else sanitizeAuthority(authority)
                }
            return parsed.buildUpon()
                .apply { sanitizedAuthority?.let { encodedAuthority(it) } }
                .build()
        }
        val portString = normalized.port.trim().ifEmpty { DEFAULT_PORT }
        val sanitizedPort = portString.toIntOrNull()?.toString() ?: DEFAULT_PORT
        val credential = buildString {
            if (normalized.username.isNotEmpty()) {
                append(Uri.encode(normalized.username))
                if (normalized.password.isNotEmpty() && includePassword) {
                    append(":")
                    append(Uri.encode(normalized.password))
                }
                append("@")
            }
        }
        val normalizedHost = normalized.host.trim()
        val authorityHost = if (":" in normalizedHost && !normalizedHost.startsWith("[")) {
            "[$normalizedHost]"
        } else {
            normalizedHost
        }
        val authority = buildString {
            append(credential)
            append(authorityHost)
            append(":")
            append(sanitizedPort)
        }

        val trimmedSlug = normalized.slug.trim()
        val slugUri = if (trimmedSlug.isNotEmpty()) {
            "rtsp://placeholder${if (trimmedSlug.startsWith("/")) trimmedSlug else "/$trimmedSlug"}".toUri()
        } else {
            null
        }
        val existingQueryParams = slugUri?.queryParameterNames.orEmpty()

        return Uri.Builder()
            .scheme("rtsp")
            .encodedAuthority(authority)
            .apply {
                slugUri?.path?.takeIf { it.isNotEmpty() }?.let { encodedPath(it) }
                slugUri?.queryParameterNames?.forEach { name ->
                    slugUri.getQueryParameters(name).forEach { value ->
                            appendQueryParameter(name, value)
                    }
                }
                if (normalized.channel.isNotBlank() && "channel" !in existingQueryParams) {
                    appendQueryParameter("channel", normalized.channel)
                }
                if (normalized.subtype.isNotBlank() && "subtype" !in existingQueryParams) {
                    appendQueryParameter("subtype", normalized.subtype)
                }
            }
            .build()
    }

}

private fun encodedAuthorityWithEncodedCredentials(fullUrl: String, includePassword: Boolean): String? {
    return try {
        val parsed = URI(fullUrl)
        val host = parsed.host ?: return null
        val portPart = if (parsed.port in 0..65535) ":${parsed.port}" else ""
        val decodedUserInfo = parsed.userInfo
        val credential = buildString {
            if (!decodedUserInfo.isNullOrEmpty()) {
                val parts = decodedUserInfo.split(":", limit = 2)
                val encodedUser = Uri.encode(parts.getOrNull(0).orEmpty())
                if (encodedUser.isNotEmpty()) {
                    append(encodedUser)
                    val encodedPass = parts.getOrNull(1)?.let { Uri.encode(it) }
                    if (includePassword && encodedPass != null) {
                        append(":")
                        append(encodedPass)
                    }
                    append("@")
                }
            }
        }
        val authorityHost = if (host.contains(":" ) && !host.startsWith("[")) "[$host]" else host
        "$credential$authorityHost$portPart"
    } catch (_: Exception) {
        null
    }
}

private fun sanitizeAuthority(authority: String): String {
    val atIndex = authority.lastIndexOf('@')
    if (atIndex <= 0) return authority
    val credential = authority.substring(0, atIndex)
    val hostPart = authority.substring(atIndex + 1)
    val colonIndex = credential.indexOf(':')
    val sanitizedCredential = if (colonIndex >= 0) credential.substring(0, colonIndex) else credential
    return if (sanitizedCredential.isEmpty()) hostPart else "$sanitizedCredential@$hostPart"
}

@OptIn(ExperimentalAnimationApi::class, ExperimentalComposeUiApi::class)
@UnstableApi
@Composable
fun RtspViewerApp() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val sharedPreferences = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }

    var statusText by remember { mutableStateOf("No camera selected") }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPreviewUrl by remember { mutableStateOf<String?>(null) }
    val cameraSlots = remember {
        mutableStateListOf<CameraConfig>().apply { repeat(SLOT_COUNT) { add(CameraConfig()) } }
    }
    val editingSlots = remember {
        mutableStateListOf<CameraConfig>().apply { repeat(SLOT_COUNT) { add(CameraConfig()) } }
    }
    val logEntries = remember { mutableStateListOf<LogEntry>() }
    val logTimeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm:ss") }
    var selectedIndex by remember { mutableIntStateOf(0) }
    var controlsVisible by remember { mutableStateOf(true) }
    var settingsVisible by remember { mutableStateOf(false) }
    var logsVisible by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var hasLoadedSettings by remember { mutableStateOf(false) }

    fun persistLogs() {
        sharedPreferences.edit {
            putString(PREFS_KEY_LOGS, serializeLogs(logEntries))
        }
    }

    fun appendLog(message: String) {
        logEntries.add(0, LogEntry(System.currentTimeMillis(), message))
        persistLogs()
    }

    fun clearLogs() {
        logEntries.clear()
        persistLogs()
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val configs = if (settingsVisible) editingSlots else cameraSlots
                exportCameraSettings(context, uri, configs)
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val imported = importCameraSettings(context, uri)
                if (imported != null) {
                    if (settingsVisible) {
                        applyCameraSettings(editingSlots, imported)
                    } else {
                        applyCameraSettings(cameraSlots, imported)
                    }
                }
            }
        }
    }

    LaunchedEffect(sharedPreferences) {
        val stored = sharedPreferences.getString(PREFS_KEY_SLOTS, null)
        val configs = parseCameraSettings(stored)
        if (configs != null) {
            applyCameraSettings(cameraSlots, configs)
        }
        val storedLogs = sharedPreferences.getString(PREFS_KEY_LOGS, null)
        val parsedLogs = parseLogs(storedLogs)
        if (parsedLogs != null) {
            logEntries.clear()
            logEntries.addAll(parsedLogs)
        }
        hasLoadedSettings = true
    }

    LaunchedEffect(hasLoadedSettings) {
        if (!hasLoadedSettings) return@LaunchedEffect
        snapshotFlow { cameraSlots.map { it } }
            .collectLatest { configs ->
                sharedPreferences.edit {
                    putString(PREFS_KEY_SLOTS, serializeCameraSettings(configs))
                }
            }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                statusText = when (playbackState) {
                    Player.STATE_IDLE -> if (currentPreviewUrl == null) "No camera selected" else "Stopped"
                    Player.STATE_BUFFERING -> "Buffering…"
                    Player.STATE_READY -> if (player.playWhenReady) "Playing" else "Paused"
                    Player.STATE_ENDED -> "Stream ended"
                    else -> statusText
                }
                appendLog("State changed: $statusText")
            }

            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                isPlaying = isPlayingNow
            }

            override fun onPlayerError(error: PlaybackException) {
                val detail = error.message ?: "Unknown"
                statusText = "Error: ${error.errorCodeName} ($detail)"
                appendLog("Playback error ${error.errorCodeName}: $detail")
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    fun stopPlayback(message: String = "Stopped") {
        player.stop()
        player.clearMediaItems()
        currentPreviewUrl = null
        isPlaying = false
        statusText = message
        appendLog(message)
    }

    fun connectToCamera(slotIndex: Int) {
        val config = cameraSlots[slotIndex]
        val normalized = config.normalized()
        val error = normalized.validationError()
        if (error != null) {
            stopPlayback(error)
            return
        }

        val playbackUri = normalized.toRtspUri(includePassword = true)
        val previewUri = normalized.toRtspUri(includePassword = false)
        val mediaItem = MediaItem.Builder()
            .setUri(playbackUri)
            .setMimeType(MimeTypes.APPLICATION_RTSP)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(normalized.displayName())
                    .build()
            )
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(normalized.latencyMs.toLong())
                    .build()
            )
            .build()

        val timeoutMs = max(5_000, normalized.latencyMs * 10).toLong()
        val mediaSource = RtspMediaSource.Factory()
            .setForceUseRtpTcp(normalized.transport == RtspTransport.TCP)
            .setTimeoutMs(timeoutMs)
            .createMediaSource(mediaItem)

        statusText = "Connecting…"
        currentPreviewUrl = previewUri.toString()
        appendLog("Connecting to ${previewUri} via ${normalized.transport} (timeout ${timeoutMs}ms)")
        player.stop()
        player.setMediaSource(mediaSource, /* resetPosition= */ true)
        player.prepare()
        player.play()
    }

    fun selectSlot(newIndex: Int, connect: Boolean) {
        val bounded = newIndex.coerceIn(0, SLOT_COUNT - 1)
        if (bounded == selectedIndex && !connect) return
        selectedIndex = bounded
        if (connect) {
            connectToCamera(bounded)
        }
    }

    fun openSettings() {
        applyCameraSettings(editingSlots, cameraSlots)
        settingsVisible = true
        controlsVisible = true
    }

    fun saveSettings() {
        val wasPlaying = isPlaying || currentPreviewUrl != null
        applyCameraSettings(cameraSlots, editingSlots)
        if (wasPlaying) {
            stopPlayback("Settings updated")
        } else if (cameraSlots[selectedIndex].validationError() != null) {
            stopPlayback("No camera selected")
        }
        settingsVisible = false
    }

    LaunchedEffect(controlsVisible, settingsVisible) {
        if (controlsVisible && !settingsVisible) {
            // When the settings screen is hidden, fade controls back out after a brief pause.
            delay(HIDE_CONTROLS_DELAY_MS)
            controlsVisible = false
        }
    }

    Crossfade(
        targetState = when {
            settingsVisible -> AppScreen.Settings
            logsVisible -> AppScreen.Logs
            else -> AppScreen.Player
        },
        label = "main_screen"
    ) { screen ->
        when (screen) {
            AppScreen.Settings -> SettingsScreen(
                slots = editingSlots,
                selectedIndex = selectedIndex,
                onSlotChange = { index, updated -> editingSlots[index] = updated },
                onClearSlot = { index -> editingSlots[index] = CameraConfig() },
                onExport = { exportLauncher.launch(EXPORT_FILE_NAME) },
                onImport = { importLauncher.launch(arrayOf("application/json", "text/*")) },
                onSave = { saveSettings() },
                onCancel = { settingsVisible = false }
            )
            AppScreen.Logs -> LogScreen(
                logEntries = logEntries,
                logTimeFormatter = logTimeFormatter,
                onClose = {
                    Toast.makeText(context, "Closing logs", Toast.LENGTH_SHORT).show()
                    logsVisible = false
                },
                onCopyLogs = {
                    appendLog("Logs copied to clipboard")
                },
                onClearLogs = {
                    clearLogs()
                    Toast.makeText(context, "Logs cleared", Toast.LENGTH_SHORT).show()
                }
            )
            AppScreen.Player -> {
            val safeDrawingPadding = WindowInsets.safeDrawing.asPaddingValues()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(selectedIndex) {
                        detectDragGestures(
                            onDragStart = { dragOffset = 0f },
                            onDrag = { change, dragAmount ->
                                dragOffset += dragAmount.x
                                @Suppress("DEPRECATION")
                                change.consumePositionChange()
                            },
                            onDragEnd = {
                                when {
                                    dragOffset > DRAG_THRESHOLD -> selectSlot(selectedIndex - 1, connect = true)
                                    dragOffset < -DRAG_THRESHOLD -> selectSlot(selectedIndex + 1, connect = true)
                                }
                                dragOffset = 0f
                            },
                            onDragCancel = {
                                dragOffset = 0f
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                controlsVisible = true
                            }
                        )
                    }
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        PlayerView(context).apply {
                            useController = false
                            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        }
                    },
                    update = { view ->
                        view.player = player
                        view.keepScreenOn = isPlaying
                    }
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(safeDrawingPadding)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Camera ${selectedIndex + 1}",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = statusText,
                        color = Color.White.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    currentPreviewUrl?.let { url ->
                        Text(
                            text = url,
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                AnimatedVisibility(
                    visible = controlsVisible,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(safeDrawingPadding)
                        .padding(24.dp),
                    enter = fadeIn(animationSpec = tween(durationMillis = 150)),
                    exit = fadeOut(animationSpec = tween(durationMillis = 150))
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                controlsVisible = true
                                if (isPlaying || currentPreviewUrl != null) {
                                    stopPlayback("Stopped")
                                } else {
                                    connectToCamera(selectedIndex)
                                }
                            }
                        ) {
                            Text(if (isPlaying || currentPreviewUrl != null) "Disconnect" else "Connect")
                        }
                        Button(onClick = { openSettings() }) {
                            Text("Settings")
                        }
                    }
                }

                IconButton(
                    onClick = { logsVisible = true },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(safeDrawingPadding)
                        .padding(16.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                            shape = MaterialTheme.shapes.small
                        )
                ) {
                    Icon(Icons.Filled.Info, contentDescription = "Open logs")
                }
            }
        }
    }
}

}

@Composable
private fun LogScreen(
    logEntries: List<LogEntry>,
    logTimeFormatter: DateTimeFormatter,
    onClose: () -> Unit,
    onCopyLogs: (String) -> Unit,
    onClearLogs: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val safeDrawingPadding = WindowInsets.safeDrawing.asPaddingValues()
    val context = LocalContext.current
    val formattedLogs = remember(logEntries) {
        logEntries.joinToString(separator = "\n") { entry ->
            val time = Instant.ofEpochMilli(entry.timestamp)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime()
            "${time.format(logTimeFormatter)} · ${entry.message}"
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Logs",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        clipboardManager.setText(AnnotatedString(formattedLogs))
                        Toast.makeText(context, "Logs copied", Toast.LENGTH_SHORT).show()
                        onCopyLogs(formattedLogs)
                    }) {
                        Text("Copy all")
                    }
                    OutlinedButton(onClick = {
                        onClearLogs()
                    }) {
                        Text("Clear")
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Close logs")
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .padding(safeDrawingPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (logEntries.isEmpty()) {
                Text(
                    text = "No log entries yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(logEntries) { entry ->
                        val time = Instant.ofEpochMilli(entry.timestamp)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDateTime()
                        Text(
                            text = "${time.format(logTimeFormatter)} · ${entry.message}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

private fun serializeLogs(entries: List<LogEntry>): String {
    val array = JSONArray()
    entries.forEach { entry ->
        array.put(
            JSONObject().apply {
                put("timestamp", entry.timestamp)
                put("message", entry.message)
            }
        )
    }
    return array.toString()
}

private fun parseLogs(json: String?): List<LogEntry>? {
    if (json.isNullOrBlank()) return null
    return try {
        val array = JSONArray(json)
        buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val timestamp = obj.optLong("timestamp", -1L)
                val message = obj.optString("message")
                if (timestamp >= 0 && message.isNotBlank()) {
                    add(LogEntry(timestamp, message))
                }
            }
        }
    } catch (_: JSONException) {
        null
    }
}

@Composable
private fun SettingsScreen(
    slots: List<CameraConfig>,
    selectedIndex: Int,
    onSlotChange: (Int, CameraConfig) -> Unit,
    onClearSlot: (Int) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Camera settings",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "Configure saved streams and import/export profiles.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = onSave,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Save")
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onExport,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Export")
                }
                OutlinedButton(
                    onClick = onImport,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Import")
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                itemsIndexed(slots) { index, slot ->
                    CameraSlotEditor(
                        index = index,
                        config = slot,
                        isSelected = index == selectedIndex,
                        onConfigChange = { updated -> onSlotChange(index, updated) },
                        onClear = { onClearSlot(index) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraSlotEditor(
    index: Int,
    config: CameraConfig,
    isSelected: Boolean,
    onConfigChange: (CameraConfig) -> Unit,
    onClear: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = if (isSelected) 4.dp else 0.dp,
        border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Slot ${index + 1}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
            )

            OutlinedTextField(
                value = config.title,
                onValueChange = { onConfigChange(config.copy(title = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Title") },
                singleLine = true
            )

            OutlinedTextField(
                value = config.fullUrl,
                onValueChange = { onConfigChange(config.copy(fullUrl = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("RTSP URL") },
                singleLine = true,
                placeholder = { Text("rtsp://user:pass@host:port/path") }
            )

            Text(
                text = "Or build the URL from individual fields:",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = config.username,
                    onValueChange = { onConfigChange(config.copy(username = it)) },
                    modifier = Modifier.weight(1f),
                    label = { Text("Username") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = config.password,
                    onValueChange = { onConfigChange(config.copy(password = it)) },
                    modifier = Modifier.weight(1f),
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = config.host,
                    onValueChange = { onConfigChange(config.copy(host = it)) },
                    modifier = Modifier.weight(2f),
                    label = { Text("Host") },
                    singleLine = true,
                    placeholder = { Text("camera.local") }
                )
                OutlinedTextField(
                    value = config.port,
                    onValueChange = { onConfigChange(config.copy(port = it)) },
                    modifier = Modifier.weight(1f),
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }

            OutlinedTextField(
                value = config.slug,
                onValueChange = { onConfigChange(config.copy(slug = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Path/slug") },
                singleLine = true,
                placeholder = { Text(DEFAULT_CAMERA_SLUG) }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = config.channel,
                    onValueChange = { onConfigChange(config.copy(channel = it)) },
                    modifier = Modifier.weight(1f),
                    label = { Text("Channel") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = config.subtype,
                    onValueChange = { onConfigChange(config.copy(subtype = it)) },
                    modifier = Modifier.weight(1f),
                    label = { Text("Subtype") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }

            Text(
                text = "Preview: ${config.toRtspUri(includePassword = false)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Transport",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RtspTransport.entries.forEach { transport ->
                        FilterChip(
                            selected = config.transport == transport,
                            onClick = { onConfigChange(config.copy(transport = transport)) },
                            label = { Text(transport.title) }
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Latency: ${config.latencyMs} ms",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                androidx.compose.material3.Slider(
                    value = config.latencyMs.toFloat(),
                    onValueChange = { onConfigChange(config.copy(latencyMs = it.roundToInt())) },
                    valueRange = 0f..2_000f
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(onClick = onClear) {
                    Text("Clear slot")
                }
            }
        }
    }
}

// Storage helpers moved to CameraSettingsStorage.kt
