package com.mangostrudel

import android.annotation.SuppressLint
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var textInput: EditText
    private lateinit var sendBtn: Button
    private lateinit var statusBar: TextView
    private lateinit var prefs: SharedPreferences
    private var apiKey: String = ""
    private var currentCode: String = ""
    private val geminiModel = "gemini-3.1-flash-lite-preview"

    private val systemPrompt = """
        You are a live coding assistant for Strudel (browser port of TidalCycles).
        Translate natural language musical instructions into valid Strudel JS code.
        Return ONLY raw runnable Strudel code. No explanation, no markdown, no backticks.
        Do NOT add .play() at the end.

        Key Strudel API:
        - Drums: sound("bd sd hh oh cp").bank("RolandTR909")
        - Notes: note("c3 e3 g3").sound("piano")
        - Stack: stack(sound("bd sd"), note("c3 e3").sound("piano"))
        - Effects: .lpf(800) .delay(0.5) .reverb(2) .gain(0.8)
        - Rhythm: .fast(2) .slow(2) .every(4, x=>x.fast(2))
        - Scales: note("0 2 4 5 7").scale("C4:minor")
        - Euclidean: sound("bd").euclid(3,8)

        If current code is provided, modify it. Otherwise create fresh.
        Always return a single complete runnable Strudel expression.
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
        statusBar = findViewById(R.id.statusBar)

        setupWebView()

        sendBtn.setOnClickListener {
            val text = textInput.text.toString().trim()
            if (text.isNotEmpty()) {
                textInput.text.clear()
                processCommand(text)
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
                // Forward Strudel errors back to status bar
                if (msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    runOnUiThread { handleStrudelError(msg.message()) }
                }
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                runOnUiThread { setStatus("Ready — type a musical command") }
            }
        }

        // Bridge for Strudel to call back into Android
        webView.addJavascriptInterface(StrudelBridge(), "AndroidBridge")
    }

    private fun loadStrudel() {
        // Load bundled Strudel from assets
        webView.loadUrl("file:///android_asset/strudel/REPL/index.html")
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
        // Send error back to Gemini for self-correction
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
        setStatus("Thinking...")
        sendBtn.isEnabled = false

        val userMsg = if (currentCode.isNotEmpty())
            "Current code:\n$currentCode\n\nInstruction: $userText"
        else userText

        Thread {
            val code = callGemini(userMsg, retry = true)
            runOnUiThread {
                sendBtn.isEnabled = true
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

            // Strip markdown fences if present
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
