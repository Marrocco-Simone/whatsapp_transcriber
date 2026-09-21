package dev.simone.watranscriber.ui

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.simone.watranscriber.audio.AudioDecoder
import dev.simone.watranscriber.data.AudioFile
import dev.simone.watranscriber.data.AudioScanner
import dev.simone.watranscriber.data.Store
import dev.simone.watranscriber.data.isNotificationAccessGranted
import dev.simone.watranscriber.whisper.Whisper
import dev.simone.watranscriber.whisper.WhisperModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PAGE_SIZE = 20

data class AudioItem(
    val path: String,
    val name: String,
    val timeMillis: Long,
    val durationMillis: Long?,
    val chat: String?,
    val sender: String?,
    val transcript: String?,
)

sealed interface ModelState {
    data object Missing : ModelState
    data class Downloading(val progress: Float) : ModelState
    data object Ready : ModelState
    data class Failed(val message: String) : ModelState
}

data class UiState(
    val model: ModelState = ModelState.Missing,
    val hasStorageAccess: Boolean = false,
    val hasNotificationAccess: Boolean = false,
    val isScanning: Boolean = false,
    val items: List<AudioItem> = emptyList(),
    val totalFound: Int = 0,
    val progress: Map<String, Int> = emptyMap(),
    val error: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val store = Store.get(application)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var files = emptyList<AudioFile>()
    private var shown = PAGE_SIZE

    fun refresh() {
        _state.update {
            it.copy(
                model = if (WhisperModel.isReady(getApplication())) ModelState.Ready else it.model,
                hasStorageAccess = Environment.isExternalStorageManager(),
                hasNotificationAccess = isNotificationAccessGranted(getApplication()),
            )
        }
        if (!Environment.isExternalStorageManager()) return
        viewModelScope.launch {
            _state.update { it.copy(isScanning = true) }
            files = withContext(Dispatchers.IO) { AudioScanner.scan() }
            _state.update { it.copy(isScanning = false, totalFound = files.size) }
            loadPage()
        }
    }

    fun loadMore() {
        shown += PAGE_SIZE
        viewModelScope.launch { loadPage() }
    }

    private suspend fun loadPage() {
        val page = files.take(shown)
        val items = withContext(Dispatchers.IO) {
            val rows = store.audioRows()
            page.map { file ->
                val row = rows[file.path]
                val label = row?.chat?.let { chat -> chat to row.sender }
                    ?: store.matchNotification(file.timeMillis)
                        ?.also { store.saveLabel(file.path, it) }
                        ?.let { it.chat to it.sender }
                AudioItem(
                    path = file.path,
                    name = file.name,
                    timeMillis = file.timeMillis,
                    durationMillis = AudioDecoder.durationMillis(file.path),
                    chat = label?.first,
                    sender = label?.second,
                    transcript = row?.transcript,
                )
            }
        }
        _state.update { it.copy(items = items) }
    }

    fun onCardClick(item: AudioItem) {
        if (item.transcript != null || item.path in _state.value.progress) return
        _state.update { it.copy(error = null) }
        transcribe(item)
    }

    private fun transcribe(item: AudioItem) {
        viewModelScope.launch {
            _state.update { it.copy(progress = it.progress + (item.path to 0)) }
            try {
                val model = WhisperModel.file(getApplication())
                val samples = withContext(Dispatchers.IO) { AudioDecoder.decode(item.path) }
                val text = Whisper.transcribe(model, samples) { percent ->
                    _state.update { it.copy(progress = it.progress + (item.path to percent)) }
                }
                withContext(Dispatchers.IO) { store.saveTranscript(item.path, text) }
                _state.update { current ->
                    current.copy(
                        progress = current.progress - item.path,
                        items = current.items.map {
                            if (it.path == item.path) it.copy(transcript = text) else it
                        },
                    )
                }
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        progress = it.progress - item.path,
                        error = error.message ?: "transcription failed",
                    )
                }
            }
        }
    }

    fun downloadModel() {
        viewModelScope.launch {
            _state.update { it.copy(model = ModelState.Downloading(0f)) }
            try {
                WhisperModel.download(getApplication()) { progress ->
                    _state.update { it.copy(model = ModelState.Downloading(progress)) }
                }
                _state.update { it.copy(model = ModelState.Ready) }
            } catch (error: Exception) {
                _state.update {
                    it.copy(model = ModelState.Failed(error.message ?: "download failed"))
                }
            }
        }
    }

    /** Frees the space the model takes. The next tap downloads it again. */
    fun deleteModel() {
        viewModelScope.launch {
            Whisper.release()
            withContext(Dispatchers.IO) { WhisperModel.delete(getApplication()) }
            _state.update { it.copy(model = ModelState.Missing) }
        }
    }

    fun wipeStoredData() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.wipe() }
            loadPage()
        }
    }

    fun releaseModel() {
        viewModelScope.launch { Whisper.release() }
    }
}
