@file:Suppress("SpellCheckingInspection")

package org.archuser.rtspview

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.consume
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.archuser.rtspview.ui.theme.RTSPViewTheme
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    @OptIn(UnstableApi::class)
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

private const val DEFAULT_CAMERA_SLUG = "/cam/realmonitor"
private const val DEFAULT_PORT = "554"
private const val DEFAULT_CHANNEL = "1"
private const val DEFAULT_SUBTYPE = "0"
private const val DEFAULT_LATENCY_MS = 100
private const val SLOT_COUNT = 16
private const val HIDE_CONTROLS_DELAY_MS = 5_000L
private const val DRAG_THRESHOLD = 120f
private const val PREFS_NAME = "camera_settings"
private const val PREFS_KEY_SLOTS = "slots"
private const val EXPORT_FILE_NAME = "rtsp_cameras.json"

private enum class RtspTransport(val title: String) {
    TCP("TCP"),
    UDP("UDP")
}

private data class CameraConfig(
    val title: String = "",
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
        username = username.trim(),
        password = password.trim(),
        host = host.trim(),
        port = port.trim().ifEmpty { DEFAULT_PORT },
        slug = slug.trim().ifEmpty { DEFAULT_CAMERA_SLUG },
        channel = channel.trim().ifEmpty { DEFAULT_CHANNEL },
        subtype = subtype.trim().ifEmpty { DEFAULT_SUBTYPE },
        latencyMs = latencyMs.coerceIn(0, 2_000)
    )

    fun validationError(): String? {
        if (host.isBlank()) return "Host/IP address is required"
        return null
    }

    fun displayName(): String =
        title.ifBlank { host.ifBlank { "RTSP Stream" } }

    fun previewUrl(includePassword: Boolean = false): String {
        val normalized = normalized()
        val credential = buildString {
            if (normalized.username.isNotEmpty()) {
                append(normalized.username)
                if (normalized.password.isNotEmpty() && includePassword) {
                    append(":")
                    append(normalized.password)
                }
                append("@")
            }
        }
        val query = "?channel=${normalized.channel}&subtype=${normalized.subtype}"
        return "rtsp://$credential${normalized.host}:${normalized.port}${normalized.slug}$query"
    }
}

@Composable
@UnstableApi
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
    var selectedIndex by remember { mutableIntStateOf(0) }
    var controlsVisible by remember { mutableStateOf(true) }
    var settingsVisible by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var hasLoadedSettings by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            coroutineScope.launch { exportCameraSettings(context, uri, cameraSlots) }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val imported = importCameraSettings(context, uri)
                if (imported != null) {
                    applyCameraSettings(cameraSlots, imported)
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
            }

            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                isPlaying = isPlayingNow
            }

            override fun onPlayerError(error: PlaybackException) {
                statusText = "Error: ${error.errorCodeName}"
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
    }

    fun connectToCamera(slotIndex: Int) {
        val config = cameraSlots[slotIndex]
        val normalized = config.normalized()
        val error = normalized.validationError()
        if (error != null) {
            stopPlayback(error)
            return
        }

        val mediaItem = MediaItem.Builder()
            .setUri(normalized.previewUrl(includePassword = true))
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
        currentPreviewUrl = normalized.previewUrl(includePassword = false)
        player.stop()
        player.setMediaSource(mediaSource)
        player.prepare()
        player.playWhenReady = true
    }

    fun selectSlot(newIndex: Int, connect: Boolean) {
        val bounded = newIndex.coerceIn(0, SLOT_COUNT - 1)
        if (bounded == selectedIndex && !connect) return
        selectedIndex = bounded
        if (connect) {
            connectToCamera(bounded)
        }
    }

    LaunchedEffect(controlsVisible, settingsVisible) {
        if (controlsVisible && !settingsVisible) {
            // When the settings drawer is hidden, fade controls back out after a brief pause.
            delay(HIDE_CONTROLS_DELAY_MS)
            controlsVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(selectedIndex) {
                detectDragGestures(
                    onDragStart = { dragOffset = 0f },
                    onDrag = { change, dragAmount ->
                        dragOffset += dragAmount.x
                        change.consume()
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
            visible = controlsVisible || settingsVisible,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(24.dp),
            enter = fadeIn(animationSpec = tween(durationMillis = 150)),
            exit = fadeOut(animationSpec = tween(durationMillis = 150))
        ) {
            Button(onClick = { settingsVisible = true; controlsVisible = true }) {
                Text("Settings")
            }
        }

        AnimatedVisibility(
            visible = settingsVisible,
            enter = fadeIn(animationSpec = tween(150)) + slideInHorizontally(initialOffsetX = { it / 2 }),
            exit = fadeOut(animationSpec = tween(150)) + slideOutHorizontally(targetOffsetX = { it / 2 })
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(start = 72.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxHeight()
                        .align(Alignment.CenterEnd)
                        .fillMaxWidth(0.55f),
                    tonalElevation = 6.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(24.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Camera settings",
                                style = MaterialTheme.typography.titleLarge
                            )
                            TextButton(onClick = { settingsVisible = false }) {
                                Text("Close")
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { exportLauncher.launch(EXPORT_FILE_NAME) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Export")
                            }
                            OutlinedButton(
                                onClick = { importLauncher.launch(arrayOf("application/json", "text/*")) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Import")
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            itemsIndexed(cameraSlots) { index, slot ->
                                CameraSlotEditor(
                                    index = index,
                                    config = slot,
                                    isSelected = index == selectedIndex,
                                    onConfigChange = { updated -> cameraSlots[index] = updated },
                                    onConnect = {
                                        selectSlot(index, connect = true)
                                        settingsVisible = false
                                        controlsVisible = true
                                    },
                                    onClear = {
                                        cameraSlots[index] = CameraConfig()
                                        if (index == selectedIndex) {
                                            stopPlayback("No camera selected")
                                        }
                                    }
                                )
                            }
                        }
                    }
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
    onConnect: () -> Unit,
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
                value = config.host,
                onValueChange = { onConfigChange(config.copy(host = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Host / IP") },
                singleLine = true
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = config.port,
                    onValueChange = { onConfigChange(config.copy(port = it)) },
                    modifier = Modifier.weight(1f),
                    label = { Text("Port") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = config.slug,
                    onValueChange = { onConfigChange(config.copy(slug = it)) },
                    modifier = Modifier.weight(1f),
                    label = { Text("Path") },
                    singleLine = true
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = config.channel,
                    onValueChange = { onConfigChange(config.copy(channel = it)) },
                    modifier = Modifier.weight(1f),
                    label = { Text("Channel") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = config.subtype,
                    onValueChange = { onConfigChange(config.copy(subtype = it)) },
                    modifier = Modifier.weight(1f),
                    label = { Text("Subtype") },
                    singleLine = true
                )
            }

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
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
            }

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
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onConnect,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Connect")
                }
                OutlinedButton(
                    onClick = onClear,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Clear")
                }
            }
        }
    }
}

private fun serializeCameraSettings(configs: List<CameraConfig>): String {
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

private fun parseCameraSettings(json: String?): List<CameraConfig>? {
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
    } catch (error: JSONException) {
        null
    }
}

private fun applyCameraSettings(target: MutableList<CameraConfig>, configs: List<CameraConfig>) {
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

private suspend fun exportCameraSettings(context: Context, uri: Uri, configs: List<CameraConfig>) {
    withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            OutputStreamWriter(stream).use { writer ->
                writer.write(serializeCameraSettings(configs))
            }
        }
    }
}

private suspend fun importCameraSettings(context: Context, uri: Uri): List<CameraConfig>? {
    val json = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BufferedReader(InputStreamReader(stream)).use { reader ->
                reader.readText()
            }
        }
    }
    return parseCameraSettings(json)
}
