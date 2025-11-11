@file:Suppress("SpellCheckingInspection")

package org.archuser.rtspview

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch
import org.archuser.rtspview.ui.theme.RTSPViewTheme
import kotlin.math.max
import kotlin.math.roundToInt

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

private const val DEFAULT_CAMERA_SLUG = "/cam/realmonitor"
private const val DEFAULT_PORT = "554"
private const val DEFAULT_CHANNEL = "1"
private const val DEFAULT_SUBTYPE = "0"
private const val DEFAULT_LATENCY_MS = 100

enum class RtspTransport(val title: String) {
    TCP("TCP"),
    UDP("UDP")
}

data class CameraConfig(
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

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun RtspViewerApp() {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var config by remember { mutableStateOf(CameraConfig()) }
    var statusText by remember { mutableStateOf("Idle") }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPreviewUrl by remember { mutableStateOf<String?>(null) }
    val savedCameras = remember { mutableStateListOf<CameraConfig>() }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                statusText = when (playbackState) {
                    Player.STATE_IDLE -> if (currentPreviewUrl == null) "Idle" else "Stopped"
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
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = error.message ?: "Playback error"
                    )
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(config) {
        currentPreviewUrl = config
            .takeIf { it.host.isNotBlank() }
            ?.previewUrl(includePassword = false)
    }

    fun connectToCamera() {
        val normalized = config.normalized()
        val error = normalized.validationError()
        if (error != null) {
            scope.launch { snackbarHostState.showSnackbar(error) }
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

    fun stopPlayback() {
        player.stop()
        player.clearMediaItems()
        statusText = "Stopped"
        isPlaying = false
    }

    fun saveCamera() {
        val normalized = config.normalized()
        val error = normalized.validationError()
        if (error != null) {
            scope.launch { snackbarHostState.showSnackbar(error) }
            return
        }
        val existingIndex = savedCameras.indexOfFirst {
            it.displayName() == normalized.displayName() &&
                it.previewUrl(includePassword = true) == normalized.previewUrl(includePassword = true)
        }
        if (existingIndex >= 0) {
            savedCameras[existingIndex] = normalized
        } else {
            savedCameras.add(0, normalized)
        }
        scope.launch {
            snackbarHostState.showSnackbar("Saved ${normalized.displayName()}")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RTSP Viewer") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PlayerCard(
                player = player,
                statusText = statusText,
                isPlaying = isPlaying,
                currentUrl = currentPreviewUrl
            )

            ConnectionDetailsCard(config = config)

            ControlSection(
                config = config,
                onConfigChange = { config = it },
                onConnect = ::connectToCamera,
                onDisconnect = ::stopPlayback,
                onSave = ::saveCamera,
                isPlaying = isPlaying
            )

            SavedCamerasSection(
                savedCameras = savedCameras,
                onLoad = { config = it },
                onForget = { toRemove -> savedCameras.remove(toRemove) }
            )
        }
    }
}

@Composable
private fun PlayerCard(
    player: ExoPlayer,
    statusText: String,
    isPlaying: Boolean,
    currentUrl: String?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.large
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    PlayerView(context).apply {
                        useController = true
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        controllerShowTimeoutMs = 0
                        player = this@PlayerCard.player
                    }
                },
                update = { view ->
                    view.player = player
                    view.keepScreenOn = isPlaying
                }
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
                if (!currentUrl.isNullOrBlank()) {
                    Text(
                        text = currentUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            val shouldShowHint = !isPlaying && statusText in setOf("Idle", "Stopped")
            if (shouldShowHint) {
                Text(
                    text = "Tap Connect to start streaming",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(12.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun ConnectionDetailsCard(config: CameraConfig) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Connection preview",
                style = MaterialTheme.typography.titleMedium
            )
            val previewText = config
                .takeIf { it.host.isNotBlank() }
                ?.previewUrl(includePassword = false)
                ?: "rtsp://<configure-your-camera>"
            AssistChip(
                onClick = {},
                enabled = false,
                label = {
                    Text(
                        text = previewText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
            Text(
                text = "Transport: ${config.transport.title} · Target latency: ${config.latencyMs} ms",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ControlSection(
    config: CameraConfig,
    onConfigChange: (CameraConfig) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSave: () -> Unit,
    isPlaying: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Camera settings",
                style = MaterialTheme.typography.titleMedium
            )

            OutlinedTextField(
                value = config.title,
                onValueChange = { onConfigChange(config.copy(title = it)) },
                label = { Text("Label") },
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = config.username,
                    onValueChange = { onConfigChange(config.copy(username = it)) },
                    label = { Text("Username") },
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = config.password,
                    onValueChange = { onConfigChange(config.copy(password = it)) },
                    label = { Text("Password") },
                    modifier = Modifier.weight(1f)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = config.host,
                    onValueChange = { onConfigChange(config.copy(host = it)) },
                    label = { Text("Host / IP") },
                    modifier = Modifier.weight(1.2f)
                )
                OutlinedTextField(
                    value = config.port,
                    onValueChange = { onConfigChange(config.copy(port = it.filter { ch -> ch.isDigit() })) },
                    label = { Text("Port") },
                    modifier = Modifier.weight(0.8f)
                )
            }

            OutlinedTextField(
                value = config.slug,
                onValueChange = { onConfigChange(config.copy(slug = it)) },
                label = { Text("Stream path") },
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = config.channel,
                    onValueChange = { onConfigChange(config.copy(channel = it.filter { ch -> ch.isDigit() })) },
                    label = { Text("Channel") },
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = config.subtype,
                    onValueChange = { onConfigChange(config.copy(subtype = it.filter { ch -> ch.isDigit() })) },
                    label = { Text("Subtype") },
                    modifier = Modifier.weight(1f)
                )
            }

            Text(
                text = "Transport",
                style = MaterialTheme.typography.titleSmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RtspTransport.entries.forEach { option ->
                    FilterChip(
                        selected = config.transport == option,
                        onClick = { onConfigChange(config.copy(transport = option)) },
                        label = { Text(option.title) }
                    )
                }
            }

            Text(
                text = "Target latency: ${config.latencyMs} ms",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = config.latencyMs.toFloat(),
                onValueChange = { onConfigChange(config.copy(latencyMs = it.roundToInt())) },
                valueRange = 0f..500f,
                steps = 10
            )

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
                    onClick = onDisconnect,
                    enabled = isPlaying,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Disconnect")
                }
            }

            TextButton(onClick = onSave) {
                Text("Save camera for quick access")
            }
        }
    }
}

@Composable
private fun SavedCamerasSection(
    savedCameras: List<CameraConfig>,
    onLoad: (CameraConfig) -> Unit,
    onForget: (CameraConfig) -> Unit
) {
    if (savedCameras.isEmpty()) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Saved cameras",
                style = MaterialTheme.typography.titleMedium
            )

            savedCameras.forEach { camera ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = camera.displayName(),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = camera.previewUrl(includePassword = false),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            TextButton(onClick = { onLoad(camera) }) {
                                Text("Load")
                            }
                            TextButton(onClick = { onForget(camera) }) {
                                Text("Remove")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewRtspViewer() {
    RTSPViewTheme {
        RtspViewerApp()
    }
}
