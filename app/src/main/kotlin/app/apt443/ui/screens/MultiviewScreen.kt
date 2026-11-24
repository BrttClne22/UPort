package app.apt443.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.navigation.NavHostController
import androidx.tv.material3.Text
import app.apt443.data.AppContainer
import app.apt443.domain.AppSettings
import app.apt443.domain.Availability
import app.apt443.domain.Camera
import app.apt443.domain.Feed
import app.apt443.domain.Liveview
import app.apt443.domain.LiveviewSlot
import app.apt443.domain.StreamQuality
import app.apt443.domain.displayLabel
import app.apt443.screensaver.SleepPreview
import app.apt443.ui.Routes
import app.apt443.ui.components.AptChip
import app.apt443.ui.components.AptFocusSurface
import app.apt443.ui.components.AptIcon
import app.apt443.ui.components.AptIconKind
import app.apt443.ui.components.EmptyState
import app.apt443.ui.components.LoadingState
import app.apt443.ui.player.LiveAudio
import app.apt443.ui.player.LiveStreamTile
import app.apt443.ui.player.liveAudioKeys
import app.apt443.ui.theme.AptColors
import app.apt443.ui.theme.AptRadius
import app.apt443.ui.theme.AptSpace
import app.apt443.ui.theme.AptType
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import kotlin.math.ceil
import kotlin.math.sqrt

/** Id of the built-in view that shows every camera. Never collides with a Protect id. */
private const val ALL_VIEW_ID = "__all__"
private const val PREFS = "multiview"
private const val PREF_VIEW = "selected_view"

/**
 * Protect liveviews on the TV, like Viewport: pick a view, every slot plays live,
 * slots with several cameras cycle on Protect's interval. OK opens a camera full screen,
 * Play/Pause (or holding OK) toggles its audio.
 */
@Composable
fun MultiviewScreen(container: AppContainer, client: OkHttpClient, nav: NavHostController) {
    val context = LocalContext.current
    val settings by container.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val feed by container.protect.cameras().collectAsStateWithLifecycle(
        initialValue = Feed(emptyList(), Availability.NeedsSetup("")),
    )
    val hidden = settings.hiddenCameraIds
    var views by remember { mutableStateOf<List<Liveview>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(savedView(context)) }
    val sleepOn by SleepPreview.open.collectAsStateWithLifecycle()
    val paused = rememberPausedForAlert(container)

    LaunchedEffect(Unit) {
        // Retry quietly until the console answers; Protect liveviews rarely change.
        while (true) {
            container.liveviews()
                .onSuccess {
                    views = it
                    loadError = null
                }
                .onFailure {
                    if (views == null) loadError = it.message
                }
            delay(if (views == null) 10_000L else 120_000L)
        }
    }

    val camerasById = feed.items.associateBy { it.id }
    val allView = Liveview(
        id = ALL_VIEW_ID,
        name = "All cameras",
        isDefault = false,
        slots = feed.items.map { LiveviewSlot(listOf(it.id)) },
    )
    val protectViews = views.orEmpty().map { view ->
        view.copy(slots = view.slots.map { slot -> slot.copy(cameraIds = slot.cameraIds.filter { it !in hidden }) })
    }
    val choices = listOf(allView) + protectViews
    val active = choices.firstOrNull { it.id == selected }
        ?: protectViews.firstOrNull { it.isDefault }
        ?: protectViews.firstOrNull()
        ?: allView

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(AptSpace.sm)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .focusRestorer()
                .focusGroup()
                .padding(horizontal = AptSpace.focusInset, vertical = AptSpace.xxs),
            horizontalArrangement = Arrangement.spacedBy(AptSpace.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            choices.forEach { view ->
                AptChip(
                    text = view.name,
                    selected = view.id == active.id,
                    icon = if (view.id == ALL_VIEW_ID) AptIconKind.Camera else AptIconKind.Tv,
                    onClick = {
                        selected = view.id
                        saveView(context, view.id)
                    },
                )
            }
            if (views == null && loadError != null) {
                Text(
                    "Protect views unavailable: $loadError",
                    fontSize = AptType.caption,
                    color = AptColors.Tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                active.id == ALL_VIEW_ID && feed.items.isEmpty() && views == null && loadError == null ->
                    LoadingState(label = "Loading views")
                active.slots.isEmpty() -> EmptyState(
                    title = if (active.id == ALL_VIEW_ID) "No cameras yet" else "This view is empty",
                    message = "Cameras appear here as soon as the console is reachable.",
                    icon = AptIconKind.Camera,
                )
                else -> MultiviewGrid(
                    view = active,
                    cameras = camerasById,
                    container = container,
                    client = client,
                    playing = !sleepOn,
                    paused = paused,
                    onOpen = { nav.navigate(Routes.live(it)) },
                )
            }
        }
    }
}

@Composable
private fun MultiviewGrid(
    view: Liveview,
    cameras: Map<String, Camera>,
    container: AppContainer,
    client: OkHttpClient,
    playing: Boolean,
    paused: Boolean,
    onOpen: (String) -> Unit,
) {
    val count = view.slots.size
    val columns = ceil(sqrt(count.toDouble())).toInt().coerceAtLeast(1)
    val rows = ceil(count / columns.toDouble()).toInt().coerceAtLeast(1)
    // The Shield decodes a handful of high streams at most; big walls use the low stream.
    val quality = when {
        count == 1 -> StreamQuality.High
        count <= 4 -> StreamQuality.Medium
        else -> StreamQuality.Low
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRestorer()
            .focusGroup()
            .padding(horizontal = AptSpace.focusInset, vertical = AptSpace.xxs),
        verticalArrangement = Arrangement.spacedBy(AptSpace.xs),
    ) {
        for (row in 0 until rows) {
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AptSpace.xs),
            ) {
                for (column in 0 until columns) {
                    val index = row * columns + column
                    Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                        val slot = view.slots.getOrNull(index)
                        if (slot != null) {
                            MultiviewSlot(
                                slot = slot,
                                slotKey = "${view.id}#$index",
                                cameras = cameras,
                                container = container,
                                client = client,
                                quality = quality,
                                playing = playing,
                                paused = paused,
                                onOpen = onOpen,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MultiviewSlot(
    slot: LiveviewSlot,
    slotKey: String,
    cameras: Map<String, Camera>,
    container: AppContainer,
    client: OkHttpClient,
    quality: StreamQuality,
    playing: Boolean,
    paused: Boolean,
    onOpen: (String) -> Unit,
) {
    val ids = slot.cameraIds.filter { it in cameras || cameras.isEmpty() }
    var position by remember(slotKey) { mutableIntStateOf(0) }
    val cameraId = ids.getOrNull(position % ids.size.coerceAtLeast(1))
    val audible by LiveAudio.audible.collectAsStateWithLifecycle()

    // Cycle on Protect's interval, but hold still while this tile is being listened to.
    LaunchedEffect(slotKey, ids.size, cameraId in audible) {
        if (ids.size < 2 || cameraId in audible) return@LaunchedEffect
        while (true) {
            delay(slot.cycleIntervalSec * 1_000L)
            position = (position + 1) % ids.size
        }
    }

    if (cameraId == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AptColors.PreviewWell, androidx.compose.foundation.shape.RoundedCornerShape(AptRadius.tile)),
        )
        return
    }
    val camera = cameras[cameraId]
    AptFocusSurface(
        onClick = { onOpen(cameraId) },
        modifier = Modifier.fillMaxSize().liveAudioKeys(cameraId),
        corner = AptRadius.tile,
        fill = AptColors.PreviewWell,
        scale = false,
    ) {
        androidx.compose.runtime.key(cameraId) {
            LiveStreamTile(
                cameraId = cameraId,
                quality = quality,
                container = container,
                client = client,
                modifier = Modifier.fillMaxSize(),
                surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                enabled = camera?.online != false,
                playing = playing,
                paused = paused,
                placeholder = {
                    AptIcon(
                        kind = if (camera?.isDoorbell == true) AptIconKind.Bell else AptIconKind.Camera,
                        modifier = Modifier.align(Alignment.Center).size(28.dp),
                        tint = AptColors.Tertiary,
                    )
                },
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000))))
                .padding(horizontal = AptSpace.sm, vertical = AptSpace.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = camera?.name?.displayLabel("Camera") ?: "Camera",
                fontSize = AptType.caption,
                fontWeight = FontWeight.SemiBold,
                color = AptColors.OnVideo,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (camera?.online == false) {
                Text("Offline", fontSize = AptType.caption, color = AptColors.Tertiary)
            }
        }
    }
}

private fun savedView(context: Context): String? = runCatching {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_VIEW, null)
}.getOrNull()

private fun saveView(context: Context, id: String) {
    runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PREF_VIEW, id).apply()
    }
}
