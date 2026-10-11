// SPDX-License-Identifier: GPL-3.0-or-later
//
// Offline speech review for Japanese vocabulary. Speech recognition is performed
// locally by Vosk. Network access is used only once to download the model archive.

package com.ichi2.anki

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/**
 * Hands-free Japanese review. Model inference and answer matching are offline.
 * The Japanese model is downloaded once to app-private storage on first use.
 */
internal class OfflineHandsFreeReview(
    context: Context,
    private val onStatus: (String) -> Unit,
    private val onTranscript: (cardId: Long, transcript: String, acceptedAnswers: List<String>) -> Unit,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val modelMutex = Mutex()
    private val stopExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var voskModel: Model? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = kotlinx.coroutines.CompletableDeferred<Boolean>()
    private var spanishVoice: Voice? = null
    private var japaneseVoice: Voice? = null
    private var startJob: Job? = null
    private var generation = 0L
    private var activeCardId = 0L
    private var active = false
    private var acceptedAnswers: List<String> = emptyList()
    private var speechService: SpeechService? = null
    private var recognizer: Recognizer? = null
    private val resultParts = mutableListOf<String>()
    private var finalizing = false
    private var pendingStop: Runnable? = null
    private var correctionAction: (() -> Unit)? = null

    init {
        tts = TextToSpeech(appContext) { status ->
            mainHandler.post {
                val engine = tts
                if (status != TextToSpeech.SUCCESS || engine == null) {
                    ttsReady.complete(false)
                    report("Offline text-to-speech could not be initialized.")
                    return@post
                }
                val voices = runCatching { engine.voices.orEmpty().toList() }.getOrDefault(emptyList())
                spanishVoice = chooseLocalVoice(voices, "es", listOf("es-MX", "es-ES", "es"))
                japaneseVoice = chooseLocalVoice(voices, "ja", listOf("ja-JP", "ja"))
                engine.setSpeechRate(0.92f)
                ttsReady.complete(true)
                if (spanishVoice == null) {
                    report("No offline Spanish voice is installed. Install a local Spanish voice in Android text-to-speech settings.")
                }
                if (japaneseVoice == null) {
                    report("No offline Japanese voice is installed. Recognition still works, but corrections cannot be read aloud.")
                }
            }
        }
        tts?.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    val id = utteranceId ?: return
                    val expectedGeneration = id.substringAfterLast('-').toLongOrNull() ?: return
                    mainHandler.post {
                        if (!isCurrent(expectedGeneration)) return@post
                        when {
                            id.startsWith("handsfree-question-") -> beginListening(expectedGeneration)
                            id.startsWith("handsfree-correction-") -> {
                                val action = correctionAction
                                correctionAction = null
                                action?.invoke()
                            }
                        }
                    }
                }

                @Deprecated("Deprecated by Android")
                override fun onError(utteranceId: String?) {
                    mainHandler.post { report("Offline text-to-speech failed; use the normal review controls.") }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    mainHandler.post { report("Offline text-to-speech failed; use the normal review controls.") }
                }
            },
        )
    }

    fun startCard(
        cardId: Long,
        prompt: String,
        expected: List<String>,
    ) {
        cancelCurrentCard()
        val requestGeneration = generation
        activeCardId = cardId
        acceptedAnswers = expected.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        active = true

        if (prompt.isBlank()) {
            active = false
            report("Hands-free review skipped: the card has no readable prompt.")
            return
        }
        if (acceptedAnswers.isEmpty()) {
            active = false
            report("Hands-free review could not find a Japanese answer field. Add a field named VoiceAccepted or a Japanese answer field.")
            return
        }

        startJob = scope.launch {
            try {
                val ready = ttsReady.await()
                if (!isCurrent(requestGeneration)) return@launch
                if (!ready) {
                    active = false
                    report("Offline text-to-speech is unavailable. Use the normal review controls.")
                    return@launch
                }
                if (spanishVoice == null) {
                    active = false
                    report("Install an offline Spanish text-to-speech voice before using hands-free review.")
                    return@launch
                }
                val model = loadModel()
                if (!isCurrent(requestGeneration)) return@launch
                // Touching the model here also ensures it has been completely opened
                // before the recognizer starts.
                @Suppress("UNUSED_VARIABLE")
                val loadedModel = model
                speakQuestion(prompt, requestGeneration)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Timber.e(e, "Unable to prepare offline hands-free review")
                if (isCurrent(requestGeneration)) {
                    active = false
                    report("Offline Japanese model unavailable: ${e.localizedMessage ?: "download failed"}. Connect once to download the model, then review offline.")
                }
            }
        }
    }

    /** Stop the current card without releasing the model or TTS engine. */
    fun cancelCurrentCard() {
        generation++
        active = false
        startJob?.cancel()
        startJob = null
        pendingStop?.let(mainHandler::removeCallbacks)
        pendingStop = null
        correctionAction = null
        tts?.stop()
        stopRecognition(cancel = true)
        resultParts.clear()
        finalizing = false
    }

    /** Speak a missed answer locally, then let the caller grade the card as Again. */
    fun speakCorrection(
        cardId: Long,
        text: String,
        onDone: () -> Unit,
    ) {
        val requestGeneration = generation
        if (!active || activeCardId != cardId) return
        correctionAction = onDone
        val engine = tts
        val voice = japaneseVoice
        if (engine == null || voice == null || text.isBlank()) {
            report("Answer: $text")
            mainHandler.postDelayed({
                if (isCurrent(requestGeneration)) {
                    val action = correctionAction
                    correctionAction = null
                    action?.invoke()
                }
            }, CORRECTION_FALLBACK_DELAY_MS)
            return
        }
        engine.voice = voice
        val result = engine.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            Bundle(),
            "handsfree-correction-$requestGeneration",
        )
        if (result == TextToSpeech.ERROR) {
            report("Could not speak the answer. Showing it briefly before marking the card Again.")
            mainHandler.postDelayed({
                if (isCurrent(requestGeneration)) {
                    val action = correctionAction
                    correctionAction = null
                    action?.invoke()
                }
            }, CORRECTION_FALLBACK_DELAY_MS)
        }
    }

    fun close() {
        cancelCurrentCard()
        scope.coroutineContext[Job]?.cancel()
        tts?.stop()
        tts?.shutdown()
        tts = null
        val modelToClose = voskModel
        voskModel = null
        stopExecutor.execute { runCatching { modelToClose?.close() } }
        stopExecutor.shutdown()
    }

    private fun speakQuestion(prompt: String, requestGeneration: Long) {
        if (!isCurrent(requestGeneration)) return
        val engine = tts ?: return
        engine.voice = spanishVoice
        report("Hands-free: speaking prompt…")
        val result = engine.speak(
            prompt,
            TextToSpeech.QUEUE_FLUSH,
            Bundle(),
            "handsfree-question-$requestGeneration",
        )
        if (result == TextToSpeech.ERROR) {
            active = false
            report("Could not read the prompt. Use the normal review controls.")
        }
    }

    private fun beginListening(requestGeneration: Long) {
        if (!isCurrent(requestGeneration) || speechService != null) return
        scope.launch {
            try {
                val model = loadModel()
                if (!isCurrent(requestGeneration)) return@launch
                val newRecognizer = withContext(Dispatchers.IO) { Recognizer(model, SAMPLE_RATE) }
                val newService =
                    withContext(Dispatchers.IO) { SpeechService(newRecognizer, SAMPLE_RATE) }
                if (!isCurrent(requestGeneration)) {
                    withContext(Dispatchers.IO) {
                        newService.cancel()
                        newService.shutdown()
                        newRecognizer.close()
                    }
                    return@launch
                }
                recognizer = newRecognizer
                speechService = newService
                resultParts.clear()
                finalizing = false
                report("Hands-free: listening…")
                newService.startListening(listenerFor(requestGeneration), MAX_LISTENING_MS)
            } catch (e: Exception) {
                Timber.e(e, "Unable to start offline recognition")
                if (isCurrent(requestGeneration)) {
                    active = false
                    stopRecognition(cancel = true)
                    report("Could not start the microphone. Check its permission and try manual review.")
                }
            }
        }
    }

    private fun listenerFor(requestGeneration: Long) =
        object : RecognitionListener {
            override fun onPartialResult(hypothesis: String?) {
                // Vosk reports partial hypotheses continuously; do not interpret them as final.
            }

            override fun onResult(hypothesis: String?) {
                handleCompletedSegment(requestGeneration, hypothesis)
            }

            override fun onFinalResult(hypothesis: String?) {
                handleFinalResult(requestGeneration, hypothesis)
            }

            override fun onError(exception: Exception?) {
                handleRecognitionError(requestGeneration, exception)
            }

            override fun onTimeout() {
                handleRecognitionTimeout(requestGeneration)
            }
        }

    private fun handleCompletedSegment(
        requestGeneration: Long,
        hypothesis: String?,
    ) {
        if (!isCurrent(requestGeneration) || finalizing) return
        val text = hypothesisText(hypothesis)
        if (text.isNotBlank()) {
            resultParts += text
            // A completed Vosk segment normally means the speaker paused. Wait briefly
            // for a second segment, then stop so the final hypothesis is flushed.
            pendingStop?.let(mainHandler::removeCallbacks)
            val stop = Runnable {
                if (isCurrent(requestGeneration)) stopForFinalResult(requestGeneration)
            }
            pendingStop = stop
            mainHandler.postDelayed(stop, END_OF_UTTERANCE_MS)
        }
    }

    private fun stopForFinalResult(requestGeneration: Long) {
        if (!isCurrent(requestGeneration) || finalizing) return
        finalizing = true
        val service = speechService ?: return
        stopExecutor.execute {
            runCatching { service.stop() }
                .onFailure { Timber.w(it, "Could not stop Vosk speech service cleanly") }
        }
    }

    private fun handleFinalResult(
        requestGeneration: Long,
        hypothesis: String?,
    ) {
        val service = speechService
        val recognizerToClose = recognizer
        speechService = null
        recognizer = null
        pendingStop?.let(mainHandler::removeCallbacks)
        pendingStop = null
        runCatching { service?.shutdown() }
        runCatching { recognizerToClose?.close() }

        if (!isCurrent(requestGeneration) || !active) return
        val finalText = hypothesisText(hypothesis)
        val pieces = resultParts.toMutableList()
        if (finalText.isNotBlank()) pieces += finalText
        resultParts.clear()
        val transcript = pieces.joinToString(" ").trim()
        if (transcript.isBlank()) {
            active = false
            report("No speech was recognized. The card was not graded.")
            return
        }
        report("Recognized: $transcript")
        onTranscript(activeCardId, transcript, acceptedAnswers)
    }

    private fun handleRecognitionTimeout(requestGeneration: Long) {
        val service = speechService
        val recognizerToClose = recognizer
        speechService = null
        recognizer = null
        runCatching { service?.shutdown() }
        runCatching { recognizerToClose?.close() }
        if (!isCurrent(requestGeneration) || !active) return
        active = false
        report("No complete answer was recognized. The card was not graded.")
    }

    private fun handleRecognitionError(
        requestGeneration: Long,
        exception: Exception?,
    ) {
        val service = speechService
        val recognizerToClose = recognizer
        speechService = null
        recognizer = null
        runCatching { service?.shutdown() }
        runCatching { recognizerToClose?.close() }
        Timber.w(exception, "Vosk recognition failed")
        if (!isCurrent(requestGeneration) || !active) return
        active = false
        report("Speech recognition failed. The card was not graded.")
    }

    private fun stopRecognition(cancel: Boolean) {
        val service = speechService
        val recognizerToClose = recognizer
        speechService = null
        recognizer = null
        if (service == null && recognizerToClose == null) return
        stopExecutor.execute {
            if (service != null) {
                runCatching {
                    if (cancel) service.cancel() else service.stop()
                }
                runCatching { service.shutdown() }
            }
            runCatching { recognizerToClose?.close() }
        }
    }

    private suspend fun loadModel(): Model =
        modelMutex.withLock {
            voskModel?.let { return@withLock it }
            val loaded =
                withContext(Dispatchers.IO) {
                    val destination = File(appContext.filesDir, MODEL_NAME)
                    if (!isValidModel(destination)) {
                        installModel(destination)
                    }
                    Model(destination.absolutePath)
                }
            voskModel = loaded
            loaded
        }

    private suspend fun installModel(destination: File) {
        report("Downloading the Japanese offline speech model (~50 MB, first use only)…")
        withContext(Dispatchers.IO) {
            val zipFile = File(appContext.cacheDir, MODEL_ARCHIVE)
            val staging = File(appContext.filesDir, "vosk-japanese-staging")
            var connection: HttpURLConnection? = null
            try {
                staging.deleteRecursively()
                staging.mkdirs()
                connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = NETWORK_CONNECT_TIMEOUT_MS
                    readTimeout = NETWORK_READ_TIMEOUT_MS
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                }
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    error("Model server returned HTTP ${connection.responseCode}")
                }
                connection.inputStream.use { input ->
                    BufferedOutputStream(FileOutputStream(zipFile)).use { output ->
                        input.copyTo(output, DEFAULT_BUFFER_SIZE)
                    }
                }
                val stagingCanonicalPath = staging.canonicalPath + File.separator
                ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val target = File(staging, entry.name).canonicalFile
                        require(target.path.startsWith(stagingCanonicalPath)) {
                            "Unsafe path in model archive"
                        }
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            BufferedOutputStream(FileOutputStream(target)).use { output ->
                                zip.copyTo(output, DEFAULT_BUFFER_SIZE)
                            }
                        }
                        zip.closeEntry()
                    }
                }
                val extracted = File(staging, MODEL_NAME)
                check(isValidModel(extracted)) { "Downloaded archive did not contain a valid Japanese model" }
                destination.deleteRecursively()
                extracted.copyRecursively(destination, overwrite = true)
                check(isValidModel(destination)) { "Could not install the Japanese model" }
            } finally {
                connection?.disconnect()
                zipFile.delete()
                staging.deleteRecursively()
            }
        }
        report("Japanese offline speech model is ready.")
    }

    private fun isValidModel(directory: File): Boolean =
        File(directory, "am/final.mdl").isFile &&
            File(directory, "conf/model.conf").isFile &&
            File(directory, "graph/Gr.fst").isFile

    private fun chooseLocalVoice(
        voices: List<Voice>,
        language: String,
        preferredTags: List<String>,
    ): Voice? =
        voices
            .filter { it.locale.language == language && !it.isNetworkConnectionRequired }
            .sortedBy { voice ->
                val index = preferredTags.indexOf(voice.locale.toLanguageTag())
                if (index < 0) preferredTags.size else index
            }.firstOrNull()

    private fun hypothesisText(json: String?): String =
        runCatching { JSONObject(json.orEmpty()).optString("text", "").trim() }.getOrDefault("")

    private fun isCurrent(requestGeneration: Long): Boolean =
        active && generation == requestGeneration

    private fun report(message: String) {
        mainHandler.post { onStatus(message) }
    }

    companion object {
        private const val MODEL_NAME = "vosk-model-small-ja-0.22"
        private const val MODEL_ARCHIVE = "vosk-model-small-ja-0.22.zip"
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip"
        private const val SAMPLE_RATE = 16_000.0f
        private const val MAX_LISTENING_MS = 15_000
        private const val END_OF_UTTERANCE_MS = 750L
        private const val CORRECTION_FALLBACK_DELAY_MS = 2_200L
        private const val NETWORK_CONNECT_TIMEOUT_MS = 20_000
        private const val NETWORK_READ_TIMEOUT_MS = 60_000

        internal fun normalizeForComparison(text: String): String =
            Normalizer
                .normalize(text, Normalizer.Form.NFKC)
                .lowercase(Locale.ROOT)
                .replace(Regex("[\\p{P}\\p{S}\\s　]+"), "")

        internal fun matchesAnswer(
            transcript: String,
            acceptedAnswers: List<String>,
        ): Boolean {
            val normalizedTranscript = normalizeForComparison(transcript)
            if (normalizedTranscript.isBlank()) return false
            return acceptedAnswers.any { normalizeForComparison(it) == normalizedTranscript }
        }
    }
}
