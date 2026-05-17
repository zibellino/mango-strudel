package com.mangostrudel

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var textInput: EditText
    private lateinit var sendBtn: Button
    private lateinit var micBtn: Button
    private lateinit var statusBar: TextView
    private lateinit var prefs: SharedPreferences
    private var apiKey: String = ""
    private var currentCode: String = ""
    private val geminiModel = "gemini-3.1-flash-lite-preview"

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    companion object {
        private const val REQUEST_RECORD_AUDIO = 101
    }

    private val systemPrompt = """
        You are a live coding assistant for Strudel (browser port of TidalCycles).
        Translate natural language musical instructions into valid Strudel JS code.
        Return ONLY raw runnable Strudel code. No explanation, no markdown, no backticks.
        Do NOT add .play() at the end.

        WORKING EXAMPLES:

        Basic beat:
        s("bd sd bd sd").bank("RolandTR909")

        Beat with hats:
        stack(
          s("bd sd bd sd").bank("RolandTR909"),
          s("hh*8").gain(0.3).bank("RolandTR909")
        )

        Full loop with bass:
        stack(
          s("bd sd bd sd").bank("RolandTR909"),
          s("hh*8").gain(0.3).bank("RolandTR909"),
          note("c2 ~ c2 ~ e2 ~ g1 ~").s("sawtooth").lpf(500).lpq(3)
        )

        Techno at 130 BPM:
        setcpm(130)
        stack(
          s("bd*4").gain(0.9),
          s("~ cp ~ cp").room(0.2),
          s("hh*16").gain(0.4).pan(sine.range(-0.5, 0.5)),
          note("c2 c2 eb2 c2").s("sawtooth").cutoff(800)
        )

        Melody:
        note("c4 e4 g4 b4 a4 f4 d4 e4").s("piano").slow(2)

        Minor scale melody:
        note("0 2 3 5 7 8 10 12").scale("c4:minor").s("piano").slow(2)

        Acid bass:
        note("[<g1 f1>/8](<3 5>,8)").s("sawtooth").lpf(sine.range(400,800).slow(16)).lpq(8)

        KEY RULES:
        - Use s() for drums/samples, note() for pitched sounds
        - .bank("RolandTR909") for TR909 drum sounds (bd=kick, sd=snare, hh=hihat, cp=clap, oh=open hat)
        - stack() to layer multiple patterns simultaneously
        - Mini notation: * repeats, ~ is rest, <> alternates cycles, [] groups, () euclidean rhythm
        - setcpm(bpm) sets tempo in BPM
        - Effects: .room() .gain() .lpf() .delay() .pan() .cutoff() .reverb()
        - Always return a single complete runnable expression
        - If modifying existing code, return the complete modified version
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("mangostrudel", Context.MODE_PRIVATE)
        apiKey = prefs.getString("gemini_key", "") ?: ""

        webView = findViewById(R.id.webView)
        textInput = findViewById(R.id.textInput)
        sendBtn = findViewById(R.id.sendBtn)
        micBtn = findViewById(R.id.micBtn)
        statusBar = findViewById(R.id.statusBar)

        setupWebView()
        setupSpeechRecognizer()

        sendBtn.setOnClickListener {
            val text = textInput.text.toString().trim()
            if (text.isNotEmpty()) {
                textInput.text.clear()
                processCommand(text)
            }
        }

        micBtn.setOnClickListener {
            if (isListening) {
                stopListening()
            } else {
                startListening()
            }
        }

        textInput.setOnEditorActionListener { _, _, _ ->
            sendBtn.performClick()
            true
        }

        if (apiKey.isEmpty()) {
            askForApiKey()
        } else {
            loadStrudel()
        }
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            micBtn.isEnabled = false
            micBtn.alpha = 0.4f
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    runOnUiThread {
                        isListening = true
                        micBtn.backgroundTintList =
                            android.content.res.ColorStateList.valueOf(0xFFe05050.toInt())
                        setStatus("🎙 Listening...")
                    }
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    runOnUiThread { setStatus("Processing speech...") }
                }
                override fun onError(error: Int) {
                    runOnUiThread {
                        stopListening()
                        val msg = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that — try again"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                            SpeechRecognizer.ERROR_NETWORK -> "Network error"
                            else -> "Speech error ($error)"
                        }
                        setStatus(msg)
                    }
                }
                override fun onResults(results: Bundle?) {
                    val matches = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()
                    runOnUiThread {
                        stopListening()
                        if (!text.isNullOrBlank()) {
                            textInput.setText(text)
                            sendBtn.performClick()
                        } else {
                            setStatus("Didn't catch that — try again")
                        }
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!partial.isNullOrBlank()) {
                        runOnUiThread { textInput.setText(partial) }
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO
            )
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun stopListening() {
        speechRecognizer?.stopListening()
        isListening = false
        micBtn.backgroundTintList =
            android.content.res.ColorStateList.valueOf(0xFF2a2a2a.toInt())
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else {
            setStatus("Microphone permission denied")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                if (msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    runOnUiThread { handleStrudelError(msg.message()) }
                }
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                webView.evaluateJavascript("""
                    setTimeout(function() {
                        var tabs = document.querySelectorAll('[role="tab"], button');
                        for (var t of tabs) {
                            if (t.textContent.trim() === '\u00d7' || t.innerHTML.includes('\u00d7')) {
                                t.click(); break;
                            }
                        }
                    }, 2000);
                """.trimIndent(), null)
                runOnUiThread { setStatus("Ready — type or speak a musical command") }
            }
        }

        webView.addJavascriptInterface(StrudelBridge(), "AndroidBridge")
    }

    private fun loadStrudel() {
        webView.loadUrl("https://strudel.cc/")
    }

    private fun injectCode(code: String) {
        val escaped = code.replace("\\", "\\\\").replace("`", "\\`").replace("$", "\\$")
        val js = """
            (function() {
                try {
                    if (typeof strudelMirror !== 'undefined') {
                        strudelMirror.setCode(`$escaped`);
                        strudelMirror.evaluate();
                        AndroidBridge.onSuccess();
                    } else {
                        AndroidBridge.onError('Strudel not ready');
                    }
                } catch(e) {
                    AndroidBridge.onError(e.message);
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun handleStrudelError(error: String) {
        setStatus("Error — fixing...")
        Thread {
            val fixed = callGemini(
                "This Strudel code has an error:\n$currentCode\n\nError: $error\n\nFix it. Return ONLY corrected Strudel code.",
                retry = false
            )
            if (fixed != null) {
                currentCode = fixed
                runOnUiThread {
                    setStatus("Fixed — applying...")
                    injectCode(fixed)
                }
            } else {
                runOnUiThread { setStatus("Could not fix: $error") }
            }
        }.start()
    }

    private fun processCommand(userText: String) {
        val lower = userText.trim().lowercase()
        if (lower == "play" || lower == "start") {
            webView.evaluateJavascript("strudelMirror?.start?.()", null)
            setStatus("▶ Playing")
            return
        }
        if (lower == "stop" || lower == "hush" || lower == "pause") {
            webView.evaluateJavascript("strudelMirror?.stop?.()", null)
            setStatus("⏹ Stopped")
            return
        }

        setStatus("Thinking...")
        sendBtn.isEnabled = false
        micBtn.isEnabled = false

        val userMsg = if (currentCode.isNotEmpty())
            "Current code:\n$currentCode\n\nInstruction: $userText"
        else userText

        Thread {
            val code = callGemini(userMsg, retry = true)
            runOnUiThread {
                sendBtn.isEnabled = true
                micBtn.isEnabled = true
                if (code != null) {
                    currentCode = code
                    setStatus("Applying code...")
                    injectCode(code)
                } else {
                    setStatus("Failed to get code from Gemini")
                }
            }
        }.start()
    }

    private fun callGemini(userMsg: String, retry: Boolean, attempt: Int = 1): String? {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$geminiModel:generateContent?key=$apiKey"
        val body = """
            {
                "contents": [
                    {"role": "user", "parts": [{"text": ${escapeJson(systemPrompt)}}]},
                    {"role": "model", "parts": [{"text": "Ready."}]},
                    {"role": "user", "parts": [{"text": ${escapeJson(userMsg)}}]}
                ]
            }
        """.trimIndent()

        return try {
            val client = okhttp3.OkHttpClient()
            val mediaType = "application/json".toMediaType()
            val requestBody = body.toRequestBody(mediaType)
            val request = okhttp3.Request.Builder()
                .url(url)
                .post(requestBody)
                .build()
            val response = client.newCall(request).execute()
            val json = response.body?.string() ?: return null
            val org = org.json.JSONObject(json)

            if (org.has("error")) {
                val err = org.getJSONObject("error")
                val code = err.optInt("code", 0)
                if (code == 429 && retry && attempt < 4) {
                    val wait = attempt * 10L * 1000
                    runOnUiThread { setStatus("Rate limited — retrying in ${attempt * 10}s...") }
                    Thread.sleep(wait)
                    return callGemini(userMsg, retry, attempt + 1)
                }
                return null
            }

            var code = org.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()

            code = code.replace(Regex("^```[\\w]*\\n?", RegexOption.MULTILINE), "")
                .replace(Regex("```$", RegexOption.MULTILINE), "")
                .trim()

            code
        } catch (e: Exception) {
            null
        }
    }

    private fun escapeJson(s: String): String {
        return "\"" + s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") + "\""
    }

    private fun askForApiKey() {
        val input = EditText(this)
        input.hint = "AIza..."
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD

        AlertDialog.Builder(this)
            .setTitle("Gemini API Key")
            .setMessage("Enter your Gemini API key. Get one free at aistudio.google.com")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isNotEmpty()) {
                    apiKey = key
                    prefs.edit().putString("gemini_key", key).apply()
                    loadStrudel()
                } else {
                    askForApiKey()
                }
            }
            .setCancelable(false)
            .show()
    }

    private fun setStatus(msg: String) {
        statusBar.text = msg
    }

    inner class StrudelBridge {
        @JavascriptInterface
        fun onSuccess() {
            runOnUiThread { setStatus("▶ Playing") }
        }

        @JavascriptInterface
        fun onError(msg: String) {
            runOnUiThread { handleStrudelError(msg) }
        }
    }
}
