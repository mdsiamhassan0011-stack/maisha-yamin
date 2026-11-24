package com.maishayamin.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private val prefs by lazy { getSharedPreferences("maisha_yamin", MODE_PRIVATE) }
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var busy = false

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var typeInput: EditText

    private val history = mutableListOf<Pair<String, String>>()

    private val idleText = "চালু করতে বড় বাটনে দুবার ট্যাপ করুন"

    // ---------- সেটিংস ----------

    private fun persona(): String = prefs.getString("persona", "Maisha") ?: "Maisha"
    private fun lang(): String = prefs.getString("lang", "bn") ?: "bn"
    private fun age(): String = prefs.getString("age", "20") ?: "20"
    private fun apiKey(): String = (prefs.getString("key", "") ?: "").trim()
    private fun model(): String =
        (prefs.getString("model", "gemini-flash-latest") ?: "gemini-flash-latest").trim()

    private fun displayName(): String = if (persona() == "Yamin") "ইয়ামিন" else "মাইশা"

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun normalizeDigits(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            sb.append(
                when (c) {
                    in '০'..'৯' -> '0' + (c - '০')
                    in '०'..'९' -> '0' + (c - '०')
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    // ---------- জীবনচক্র ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        tts = TextToSpeech(this, this)
        if (apiKey().isEmpty()) {
            setStatus("প্রথমে নিচের ঘরে Gemini API key বসিয়ে \"সংরক্ষণ করুন\" চাপুন।")
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        recognizer?.destroy()
        super.onDestroy()
    }

    // ---------- স্ক্রিন ----------

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(32))
            setBackgroundColor(Color.parseColor("#FFF7FB"))
        }
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        val title = TextView(this).apply {
            text = "মাইশা ও ইয়ামিন"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#C2185B"))
            gravity = Gravity.CENTER
        }
        root.addView(title)

        // কে কথা বলবে
        val personaGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rM = RadioButton(this).apply {
            text = "মাইশা (মেয়ে)"
            id = View.generateViewId()
        }
        val rY = RadioButton(this).apply {
            text = "ইয়ামিন (ছেলে)"
            id = View.generateViewId()
        }
        personaGroup.addView(rM)
        personaGroup.addView(rY)
        personaGroup.check(if (persona() == "Yamin") rY.id else rM.id)
        personaGroup.setOnCheckedChangeListener { _, checkedId ->
            prefs.edit().putString("persona", if (checkedId == rY.id) "Yamin" else "Maisha").apply()
            applyTtsLanguage()
            setStatus(idleText)
        }
        root.addView(personaGroup)

        // ভাষা
        val langGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rBn = RadioButton(this).apply {
            text = "বাংলা"
            id = View.generateViewId()
        }
        val rHi = RadioButton(this).apply {
            text = "हिन्दी"
            id = View.generateViewId()
        }
        langGroup.addView(rBn)
        langGroup.addView(rHi)
        langGroup.check(if (lang() == "hi") rHi.id else rBn.id)
        langGroup.setOnCheckedChangeListener { _, checkedId ->
            prefs.edit().putString("lang", if (checkedId == rHi.id) "hi" else "bn").apply()
            if (!applyTtsLanguage()) {
                setStatus("ফোনে এই ভাষার ভয়েস নেই। Settings > Text-to-speech থেকে Google ভয়েস ডাউনলোড করুন।")
            } else {
                setStatus(idleText)
            }
        }
        root.addView(langGroup)

        // বয়স, API key, মডেল
        val ageInput = EditText(this).apply {
            hint = "বয়স (১৮ থেকে ৮০)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(age())
        }
        root.addView(ageInput)

        val keyInput = EditText(this).apply {
            hint = "Gemini API key এখানে বসান"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(apiKey())
        }
        root.addView(keyInput)

        val modelInput = EditText(this).apply {
            hint = "মডেলের নাম"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(model())
        }
        root.addView(modelInput)

        val saveButton = Button(this).apply { text = "সংরক্ষণ করুন" }
        saveButton.setOnClickListener {
            val a = normalizeDigits(ageInput.text.toString()).filter { it.isDigit() }.toIntOrNull()
            if (a == null || a < 18 || a > 80) {
                setStatus("বয়স ১৮ থেকে ৮০ এর মধ্যে দিন।")
            } else {
                prefs.edit()
                    .putString("age", a.toString())
                    .putString("key", keyInput.text.toString().trim())
                    .putString(
                        "model",
                        modelInput.text.toString().trim().ifEmpty { "gemini-flash-latest" }
                    )
                    .apply()
                setStatus("সংরক্ষণ হয়েছে। এখন বড় বাটনে দুবার ট্যাপ করে কথা বলুন।")
            }
        }
        root.addView(saveButton)

        // বড় বাটন
        val micButton = TextView(this).apply {
            text = "🎤\nডাবল ট্যাপ"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E91E8C"))
            }
        }
        val lp = LinearLayout.LayoutParams(dp(180), dp(180)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(20)
            bottomMargin = dp(8)
        }
        root.addView(micButton, lp)

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                startListening()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                setStatus(idleText)
                return true
            }
        })
        micButton.setOnTouchListener { _, ev ->
            detector.onTouchEvent(ev)
            true
        }

        statusView = TextView(this).apply {
            text = idleText
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#555555"))
        }
        root.addView(statusView)

        // লিখে প্রশ্ন করার ঘর
        typeInput = EditText(this).apply {
            hint = "অথবা এখানে লিখে প্রশ্ন করুন"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        root.addView(typeInput)

        val sendButton = Button(this).apply { text = "পাঠান" }
        sendButton.setOnClickListener {
            val t = typeInput.text.toString().trim()
            if (t.isNotEmpty() && !busy) {
                typeInput.setText("")
                ask(t)
            }
        }
        root.addView(sendButton)

        logView = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.parseColor("#222222"))
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(logView)
    }

    private fun setStatus(s: String) {
        statusView.text = s
    }

    private fun appendLog(s: String) {
        logView.append(s + "\n\n")
    }

    // ---------- কথা শোনা ----------

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            busy = false
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "কিছু শুনতে পাইনি। আবার দুবার ট্যাপ করুন।"
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ইন্টারনেটে সমস্যা। নেট চেক করুন।"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "মাইক্রোফোনের অনুমতি দিন।"
                else -> "শোনার সময় সমস্যা হয়েছে (কোড $error)।"
            }
            setStatus(msg)
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (text.isNullOrBlank()) {
                busy = false
                setStatus("কিছু শুনতে পাইনি। আবার দুবার ট্যাপ করুন।")
            } else {
                ask(text)
            }
        }
    }

    private fun startListening() {
        if (busy) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus("এই ফোনে ভয়েস শোনার সার্ভিস পাওয়া যায়নি। Google অ্যাপ আপডেট করুন, অথবা লিখে প্রশ্ন করুন।")
            return
        }
        tts?.stop()
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(listener)
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (lang() == "hi") "hi-IN" else "bn-BD")
        }
        busy = true
        setStatus("শুনছি... বলুন")
        recognizer?.startListening(intent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setStatus("অনুমতি পেয়েছি। এবার আবার দুবার ট্যাপ করুন।")
            } else {
                setStatus("মাইক্রোফোনের অনুমতি ছাড়া শুনতে পারব না। লিখে প্রশ্ন করতে পারেন।")
            }
        }
    }

    // ---------- এআই-কে জিজ্ঞেস করা ----------

    private fun systemPrompt(): String {
        val name = if (persona() == "Yamin") "Yamin" else "Maisha"
        val gender = if (persona() == "Yamin") "boy/man" else "girl/woman"
        val langName = if (lang() == "hi") "Hindi (Devanagari script)" else "Bangla (Bengali script)"
        return "You are $name, a ${age()}-year-old $gender, and the user's very close, warm, " +
            "playful, platonic friend. Talk exactly like a real friend of that age would. " +
            "Always reply only in $langName. Use correct grammar for a $gender speaker " +
            "(this matters in Hindi). Your reply is read aloud, so keep it short " +
            "(1 to 3 sentences unless the user asks for more), with no markdown, no lists " +
            "and no emojis. When the user says something right or wrong, tell them kindly " +
            "and explain why. In this version you cannot control the phone " +
            "(calls, torch, battery, messages, opening apps). If asked to do such a thing, " +
            "say honestly that this feature is coming soon. Never pretend you did it."
    }

    private fun ask(userText: String) {
        appendLog("আপনি: $userText")
        val key = apiKey()
        if (key.isEmpty()) {
            busy = false
            setStatus("আগে নিচের ঘরে Gemini API key বসিয়ে সংরক্ষণ করুন।")
            return
        }
        busy = true
        setStatus("ভাবছি...")
        val name = displayName()
        Thread {
            try {
                val reply = callGemini(userText, key)
                runOnUiThread {
                    history.add("user" to userText)
                    history.add("model" to reply)
                    appendLog("$name: $reply")
                    busy = false
                    speak(reply)
                }
            } catch (e: IOException) {
                runOnUiThread {
                    busy = false
                    setStatus("ইন্টারনেট সংযোগ চেক করুন।")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    setStatus(e.message ?: "কিছু একটা সমস্যা হয়েছে।")
                }
            }
        }.start()
    }

    private fun callGemini(userText: String, key: String): String {
        val contents = JSONArray()
        for ((role, text) in history.takeLast(12)) {
            contents.put(
                JSONObject()
                    .put("role", role)
                    .put("parts", JSONArray().put(JSONObject().put("text", text)))
            )
        }
        contents.put(
            JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", userText)))
        )
        val body = JSONObject()
            .put(
                "system_instruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", systemPrompt()))
                )
            )
            .put("contents", contents)
            .put(
                "generationConfig",
                JSONObject().put("maxOutputTokens", 2048).put("temperature", 0.8)
            )

        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                model() + ":generateContent"
        )
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 40000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                throw Exception(
                    when (code) {
                        429 -> "অনেক বেশি প্রশ্ন হয়ে গেছে। এক মিনিট পরে চেষ্টা করুন।"
                        400, 401, 403 -> "API key বা মডেলের নাম ঠিক আছে কি দেখুন (কোড $code)।"
                        404 -> "মডেলের নাম পাওয়া যায়নি। মডেল ঘরে অন্য নাম দিন (কোড 404)।"
                        else -> "সার্ভারে সমস্যা (কোড $code)।"
                    }
                )
            }

            val parts = JSONObject(raw)
                .optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
            val sb = StringBuilder()
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val p = parts.optJSONObject(i) ?: continue
                    if (p.optBoolean("thought", false)) continue
                    sb.append(p.optString("text", ""))
                }
            }
            val reply = sb.toString().trim()
            if (reply.isEmpty()) throw Exception("এআই কোনো উত্তর দেয়নি, আবার চেষ্টা করুন।")
            return reply
        } finally {
            conn.disconnect()
        }
    }

    // ---------- কথা বলা ----------

    private fun localeFor(): Locale =
        if (lang() == "hi") Locale("hi", "IN") else Locale("bn", "BD")

    private fun applyTtsLanguage(): Boolean {
        val t = tts ?: return false
        if (!ttsReady) return false
        val r = t.setLanguage(localeFor())
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            return false
        }
        t.setPitch(if (persona() == "Yamin") 0.8f else 1.2f)
        t.setSpeechRate(1.0f)
        return true
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    runOnUiThread { setStatus(idleText) }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    runOnUiThread { setStatus(idleText) }
                }
            })
            if (!applyTtsLanguage()) {
                setStatus("ফোনে এই ভাষার ভয়েস নেই। Settings > Text-to-speech থেকে Google ভয়েস ডাউনলোড করুন।")
            }
        } else {
            setStatus("ফোনের বলার ইঞ্জিন চালু হয়নি।")
        }
    }

    private fun speak(text: String) {
        if (!applyTtsLanguage()) {
            setStatus("উত্তর নিচে লেখা আছে। ফোনে ভয়েস ইনস্টল না থাকায় বলতে পারছি না।")
            return
        }
        setStatus("বলছি...")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u1")
    }
}
