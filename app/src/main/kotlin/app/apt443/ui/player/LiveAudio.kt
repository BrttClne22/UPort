package app.apt443.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Which live cameras are audible. Several can be on at once; Android mixes them.
 * Lives for the process only, so every camera starts muted after a restart.
 */
object LiveAudio {
    private val state = MutableStateFlow<Set<String>>(emptySet())
    val audible: StateFlow<Set<String>> = state.asStateFlow()

    fun isAudible(cameraId: String): Boolean = cameraId in state.value

    fun toggle(cameraId: String) {
        if (cameraId.isBlank()) return
        state.update { if (cameraId in it) it - cameraId else it + cameraId }
    }

    fun set(cameraId: String, on: Boolean) {
        if (cameraId.isBlank()) return
        state.update { if (on) it + cameraId else it - cameraId }
    }

    fun muteAll() {
        state.value = emptySet()
    }
}

/**
 * Remote keys that flip a camera's audio: Play/Pause, or holding OK.
 * A held OK is swallowed through key-up so it does not also open the camera.
 */
fun Modifier.liveAudioKeys(cameraId: String): Modifier = composed {
    var held by remember(cameraId) { mutableStateOf(false) }
    onPreviewKeyEvent { event ->
        val key = event.key
        val isOk = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter
        when {
            key == Key.MediaPlayPause && event.type == KeyEventType.KeyDown -> {
                LiveAudio.toggle(cameraId)
                true
            }
            isOk && event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount > 0 -> {
                if (!held) {
                    held = true
                    LiveAudio.toggle(cameraId)
                }
                true
            }
            isOk && event.type == KeyEventType.KeyUp && held -> {
                held = false
                true
            }
            else -> false
        }
    }
}
