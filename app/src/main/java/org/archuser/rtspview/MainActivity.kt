@file:Suppress("SpellCheckingInspection")

package org.archuser.rtspview

import android.content.Context
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.pointer.consumePositionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.archuser.rtspview.ui.theme.RTSPViewTheme
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
private const val EXPORT_FILE_NAME = "rtsp_cameras.json"

internal enum class RtspTransport(val title: String) {
    TCP("TCP"),
    UDP("UDP")
}

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
            val parsed = Uri.parse(normalized.fullUrl)
            val sanitizedAuthority = parsed.encodedAuthority?.let { authority ->
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
            Uri.parse("rtsp://placeholder${if (trimmedSlug.startsWith("/")) trimmedSlug else "/$trimmedSlug"}")
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

private fun sanitizeAuthority(authority: String): String {
    val atIndex = authority.lastIndexOf('@')
    if (atIndex <= 0) return authority
    val credential = authority.substring(0, atIndex)
    val hostPart = authority.substring(atIndex + 1)
    val colonIndex = credential.indexOf(':')
    val sanitizedCredential = if (colonIndex >= 0) credential.substring(0, colonIndex) else credential
    return if (sanitizedCredential.isEmpty()) hostPart else "$sanitizedCredential@$hostPart"
}

@OptIn(ExperimentalAnimationApi::class)
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
    var selectedIndex by remember { mutableIntStateOf(0) }
    var controlsVisible by remember { mutableStateOf(true) }
    var settingsVisible by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var hasLoadedSettings by remember { mutableStateOf(false) }
    var pendingIdleSuppressions by remember { mutableIntStateOf(0) }

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
                when (playbackState) {
                    Player.STATE_IDLE -> {
                        if (pendingIdleSuppressions > 0) {
                            pendingIdleSuppressions -= 1
                            return
                        }
                        statusText = if (currentPreviewUrl == null) "No camera selected" else "Stopped"
                        isPlaying = false
                    }

                    Player.STATE_BUFFERING -> statusText = "Buffering…"

                    Player.STATE_READY -> {
                        statusText = if (player.playWhenReady) "Playing" else "Paused"
                        isPlaying = player.playWhenReady
                    }

                    Player.STATE_ENDED -> {
                        statusText = "Stream ended"
                        isPlaying = false
                        currentPreviewUrl = null
                    }

                    else -> Unit
                }
            }

            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                isPlaying = isPlayingNow
                if (player.playbackState == Player.STATE_READY) {
                    statusText = if (isPlayingNow) "Playing" else "Paused"
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                pendingIdleSuppressions = 0
                isPlaying = false
                currentPreviewUrl = null
                val detail = error.message ?: "Unknown"
                statusText = "Error: ${error.errorCodeName} ($detail)"
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    fun stopPlayback(message: String = "Stopped") {
        if (player.playbackState != Player.STATE_IDLE) {
            pendingIdleSuppressions += 1
        }
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

        val playbackUri = normalized.toRtspUri(includePassword = true)
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

        if (player.playbackState != Player.STATE_IDLE) {
            pendingIdleSuppressions += 1
        }
        player.stop()
        player.clearMediaItems()
        statusText = "Connecting…"
        currentPreviewUrl = normalized.toRtspUri(includePassword = false).toString()
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

    Crossfade(targetState = settingsVisible, label = "settings_screen") { showSettings ->
        if (showSettings) {
            SettingsScreen(
                slots = editingSlots,
                selectedIndex = selectedIndex,
                onSlotChange = { index, updated -> editingSlots[index] = updated },
                onClearSlot = { index -> editingSlots[index] = CameraConfig() },
                onExport = { exportLauncher.launch(EXPORT_FILE_NAME) },
                onImport = { importLauncher.launch(arrayOf("application/json", "text/*")) },
                onSave = { saveSettings() },
                onCancel = { settingsVisible = false }
            )
        } else {
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
            }
        }
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
                label = { Text("Full RTSP URL (optional)") },
                singleLine = true,
                placeholder = { Text("rtsp://user:pass@host:port/path") },
                supportingText = {
                    Text(
                        text = "Leave blank to build a URL using the host, credentials, and path fields below.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            )

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Credentials",
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
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Host & path",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                OutlinedTextField(
                    value = config.host,
                    onValueChange = { onConfigChange(config.copy(host = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Hostname or IP") },
                    singleLine = true,
                    placeholder = { Text("10.0.0.5") }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = config.port,
                        onValueChange = { onConfigChange(config.copy(port = it)) },
                        modifier = Modifier.weight(1f),
                        label = { Text("Port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = config.slug,
                        onValueChange = { onConfigChange(config.copy(slug = it)) },
                        modifier = Modifier.weight(1f),
                        label = { Text("Path / slug") },
                        singleLine = true,
                        placeholder = { Text("/cam/realmonitor") }
                    )
                }
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
