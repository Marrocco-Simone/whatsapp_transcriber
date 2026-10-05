package dev.simone.watranscriber.ui

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.simone.watranscriber.audio.AudioDecoder
import dev.simone.watranscriber.data.AudioFile
import dev.simone.watranscriber.data.AudioScanner
import dev.simone.watranscriber.data.Language
import dev.simone.watranscriber.data.Model
import dev.simone.watranscriber.data.Settings
import dev.simone.watranscriber.data.Store
import dev.simone.watranscriber.data.isNotificationAccessGranted
import dev.simone.watranscriber.whisper.Whisper
import dev.simone.watranscriber.whisper.Whistle
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
    val tookMillis: Long?,
)

data class Running(val percent: Int, val startedAt: Long)

enum class EngineState { UNLOADED, LOADING, LOADED }

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
    val running: Map<String, Running> = emptyMap(),
    val language: Language = Language.ITALIAN,
    val selectedModel: Model = Model.TURBO,
    val engine: EngineState = EngineState.UNLOADED,
    val error: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val store = Store.get(application)
    private val settings = Settings(application)
    private val _state = MutableStateFlow(
        UiState(language = settings.language, selectedModel = settings.model)
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var files = emptyList<AudioFile>()
    private var shown = PAGE_SIZE

    fun refresh() {
        _state.update {
            it.copy(
                model = if (WhisperModel.isReady(getApplication(), it.selectedModel)) {
                    ModelState.Ready
                } else {
                    it.model
                },
                hasStorageAccess = Environment.isExternalStorageManager(),
                hasNotificationAccess = isNotificationAccessGranted(getApplication()),
            )
        }
        if (_state.value.model is ModelState.Ready) loadModel()
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
                    tookMillis = row?.tookMillis,
                )
            }
        }
        _state.update { it.copy(items = items) }
    }

    fun onCardClick(item: AudioItem) {
        if (item.transcript != null || item.path in _state.value.running) return
        _state.update { it.copy(error = null) }
        transcribe(item)
    }

    private fun transcribe(item: AudioItem) {
        viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            _state.update { it.copy(running = it.running + (item.path to Running(0, startedAt))) }
            try {
                val selected = _state.value.selectedModel
                val model = WhisperModel.file(getApplication(), selected)
                val samples = withContext(Dispatchers.IO) { AudioDecoder.decode(item.path) }
                val language = _state.value.language
                val onProgress = { percent: Int ->
                    _state.update {
                        it.copy(running = it.running + (item.path to Running(percent, startedAt)))
                    }
                }
                val text = if (selected == Model.WHISTLE) {
                    val code = language.code.takeIf { language != Language.AUTO }
                    Whistle.transcribe(model, samples, code, onProgress)
                } else {
                    Whisper.transcribe(model, samples, language.code, onProgress)
                }
                val took = System.currentTimeMillis() - startedAt
                withContext(Dispatchers.IO) { store.saveTranscript(item.path, text, took) }
                _state.update { current ->
                    current.copy(
                        running = current.running - item.path,
                        items = current.items.map {
                            if (it.path == item.path) {
                                it.copy(transcript = text, tookMillis = took)
                            } else {
                                it
                            }
                        },
                    )
                }
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        running = it.running - item.path,
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
                WhisperModel.download(getApplication(), _state.value.selectedModel) { progress ->
                    _state.update { it.copy(model = ModelState.Downloading(progress)) }
                }
                _state.update { it.copy(model = ModelState.Ready) }
                loadModel()
            } catch (error: Exception) {
                _state.update {
                    it.copy(model = ModelState.Failed(error.message ?: "download failed"))
                }
            }
        }
    }

    private fun loadModel() {
        if (_state.value.engine != EngineState.UNLOADED) return
        _state.update { it.copy(engine = EngineState.LOADING) }
        viewModelScope.launch {
            try {
                val selected = _state.value.selectedModel
                val model = WhisperModel.file(getApplication(), selected)
                if (selected == Model.WHISTLE) Whistle.load(model) else Whisper.load(model)
                _state.update {
                    if (it.engine == EngineState.LOADING) it.copy(engine = EngineState.LOADED) else it
                }
            } catch (error: Exception) {
                _state.update {
                    it.copy(engine = EngineState.UNLOADED, error = error.message ?: "model load failed")
                }
            }
        }
    }

    fun setLanguage(language: Language) {
        settings.language = language
        _state.update { it.copy(language = language) }
    }

    /** Switches to [model] and deletes the file of the model used before. */
    fun setModel(model: Model) {
        val current = _state.value
        if (model == current.selectedModel || current.model is ModelState.Downloading) return
        settings.model = model
        _state.update { it.copy(selectedModel = model, model = ModelState.Missing) }
        viewModelScope.launch {
            Whisper.release()
            _state.update { it.copy(engine = EngineState.UNLOADED) }
            withContext(Dispatchers.IO) { WhisperModel.deleteOthers(getApplication(), model) }
            if (_state.value.selectedModel != model) return@launch
            if (WhisperModel.isReady(getApplication(), model)) {
                _state.update { it.copy(model = ModelState.Ready) }
                loadModel()
            }
        }
    }

    /** Frees the space the model takes. The next tap downloads it again. */
    fun deleteModel() {
        viewModelScope.launch {
            Whisper.release()
            withContext(Dispatchers.IO) {
                WhisperModel.delete(getApplication(), _state.value.selectedModel)
            }
            _state.update { it.copy(model = ModelState.Missing, engine = EngineState.UNLOADED) }
        }
    }

    fun wipeStoredData() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.wipe() }
            loadPage()
        }
    }

    fun releaseModel() {
        viewModelScope.launch {
            Whisper.release()
            _state.update { it.copy(engine = EngineState.UNLOADED) }
        }
    }
}
