package com.example.gemini.ui.components

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import java.util.Locale

/**
 * High quality On-Device Speech Manager for AntiGem AI Messages.
 * Uses Google Speech Services / Android System TTS with smart Hindi/English language detection
 * and markdown preprocessing to deliver natural, human-like playback.
 */
object AntiGemSpeechManager {
    private const val TAG = "AntiGemSpeech"
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var currentContext: Context? = null

    private val _currentlySpeakingMessageId = mutableStateOf<String?>(null)
    val currentlySpeakingMessageId: State<String?> = _currentlySpeakingMessageId

    fun isSpeaking(messageId: String): Boolean {
        return _currentlySpeakingMessageId.value == messageId
    }

    private fun initTts(context: Context, onReady: () -> Unit) {
        if (tts != null && isInitialized) {
            onReady()
            return
        }

        currentContext = context.applicationContext
        tts = TextToSpeech(currentContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                setupProgressListener()
                onReady()
            } else {
                Log.e(TAG, "TTS Initialization failed with status: $status")
                _currentlySpeakingMessageId.value = null
            }
        }
    }

    private fun setupProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                _currentlySpeakingMessageId.value = null
            }

            override fun onError(utteranceId: String?) {
                _currentlySpeakingMessageId.value = null
            }
        })
    }

    fun speak(context: Context, messageId: String, rawContent: String) {
        if (isSpeaking(messageId)) {
            stop()
            return
        }

        stop()
        initTts(context) {
            val speechText = cleanMarkdownForSpeech(rawContent)
            if (speechText.isBlank()) {
                _currentlySpeakingMessageId.value = null
                return@initTts
            }

            _currentlySpeakingMessageId.value = messageId

            // Smart language detection: check for Devanagari script (Hindi)
            val hasHindi = speechText.any { it in '\u0900'..'\u097F' }
            val locale = if (hasHindi) Locale("hi", "IN") else Locale.getDefault()

            try {
                val langResult = tts?.setLanguage(locale)
                if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    // Fallback to English if Hindi voice data not available
                    tts?.setLanguage(Locale.US)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set language: ${e.message}")
            }

            tts?.setPitch(1.0f)
            tts?.setSpeechRate(1.0f)

            // TextToSpeech has max 4000 chars per speak call; split into chunks if long
            val maxChunk = 3500
            val chunks = speechText.chunked(maxChunk)
            for (i in chunks.indices) {
                val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                val params = Bundle()
                val utteranceId = "${messageId}_$i"
                tts?.speak(chunks[i], mode, params, utteranceId)
            }
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping TTS: ${e.message}")
        }
        _currentlySpeakingMessageId.value = null
    }

    /**
     * Cleans markdown syntax, code snippets, URLs, and symbols so the speech sounds
     * like a natural human conversation instead of reading markup tags aloud.
     */
    fun cleanMarkdownForSpeech(text: String): String {
        var clean = text

        // Replace multi-line code blocks ```...``` with speech cue
        clean = clean.replace(Regex("```[\\s\\S]*?```"), " (यहाँ कोड दिया गया है) ")

        // Replace inline code `code` with clean text
        clean = clean.replace(Regex("`([^`]+)`"), "$1")

        // Replace markdown images ![alt](url) with empty
        clean = clean.replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), "")

        // Replace markdown links [text](url) with just text
        clean = clean.replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")

        // Remove markdown headings #, ##, ###
        clean = clean.replace(Regex("(?m)^#{1,6}\\s+"), "")

        // Remove bold / italics **text**, *text*, __text__
        clean = clean.replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        clean = clean.replace(Regex("\\*([^*]+)\\*"), "$1")
        clean = clean.replace(Regex("__([^_]+)__"), "$1")
        clean = clean.replace(Regex("_([^_]+)_"), "$1")

        // Remove blockquotes >
        clean = clean.replace(Regex("(?m)^>\\s*"), "")

        // Remove horizontal rules
        clean = clean.replace(Regex("(?m)^[-*_]{3,}\\s*$"), "")

        // Remove table formatting pipes
        clean = clean.replace("|", " ")

        // Collapse multiple spaces and newlines
        clean = clean.replace(Regex("\\s+"), " ").trim()

        return clean
    }
}
