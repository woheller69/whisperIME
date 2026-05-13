package com.whispertflite.engine

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.whispertflite.asr.RecordBuffer
import com.whispertflite.asr.Whisper
import com.whispertflite.asr.WhisperResult
import java.io.IOException

class WhisperEngineOnnx(private val context: Context) : WhisperEngine {
    private val tag = "WhisperEngineOnnx"

    private var initialized = false
    private var recognizer: OfflineRecognizer? = null
    private var translateRecognizer: OfflineRecognizer? = null

    override fun isInitialized() = initialized

    // encoderPath = absolute path to *-encoder.int8.onnx
    // tokensPath  = absolute path to *-tokens.txt
    // Decoder path is derived by replacing "-encoder." with "-decoder."
    @Throws(IOException::class)
    override fun initialize(encoderPath: String, tokensPath: String, multilingual: Boolean) {
        val decoderPath = encoderPath.replace("-encoder.", "-decoder.")
        val threads = Runtime.getRuntime().availableProcessors()

        recognizer = buildRecognizer(encoderPath, decoderPath, tokensPath, "zh", "transcribe", threads)
        translateRecognizer = buildRecognizer(encoderPath, decoderPath, tokensPath, "zh", "translate", threads)
        initialized = true
        Log.d(tag, "Initialized: $encoderPath")
    }

    private fun buildRecognizer(
        encoder: String, decoder: String, tokens: String,
        language: String, task: String, numThreads: Int,
    ): OfflineRecognizer {
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = encoder,
                    decoder = decoder,
                    language = language,
                    task = task,
                ),
                tokens = tokens,
                numThreads = numThreads,
                modelType = "whisper",
            ),
            decodingMethod = "greedy_search",
        )
        return OfflineRecognizer(config = config)
    }

    override fun deinitialize() {
        recognizer?.release()
        recognizer = null
        translateRecognizer?.release()
        translateRecognizer = null
        initialized = false
    }

    override fun processRecordBuffer(mAction: Whisper.Action, mLangToken: Int): WhisperResult {
        val samples = RecordBuffer.getSamples()
            ?: return WhisperResult("", "zh", mAction)

        val rec = if (mAction == Whisper.Action.TRANSLATE) translateRecognizer else recognizer
            ?: return WhisperResult("", "zh", mAction)

        val stream = rec.createStream()
        stream.acceptWaveform(samples, 16000)
        rec.decode(stream)
        val result = rec.getResult(stream)
        stream.release()

        val lang = if (result.lang.isNotEmpty()) result.lang else "zh"
        Log.d(tag, "Result: '${result.text}' lang=$lang")
        return WhisperResult(result.text, lang, mAction)
    }
}
