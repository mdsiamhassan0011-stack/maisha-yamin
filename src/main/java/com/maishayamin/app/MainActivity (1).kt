package com.maishayamin.app

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
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
    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var busy = false

    // টানা কথোপকথন চালু আছে কি না
    private var conversation = false
    private var silentCount = 0

    private lateinit var statusView: TextView
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
            setStatus("প্রথমে ওপরের সেটিংসে Gemini API key বসিয়ে \"সংরক্ষণ করুন\" চাপুন।")
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        tts?.stop()
        tts?.shutdown()
        recognizer?.destroy()
        super.onDestroy()
    }

    // ---------- স্ক্রিন ----------

    private lateinit var rootLayout: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var titleView: TextView
    private lateinit var taglineView: TextView
    private lateinit var orb: OrbView
    private lateinit var chatBox: LinearLayout
    private lateinit var pM: TextView
    private lateinit var pY: TextView
    private lateinit var lBn: TextView
    private lateinit var lHi: TextView
    private lateinit var saveButton: Button
    private lateinit var sendBtn: TextView

    private fun c(s: String): Int = Color.parseColor(s)

    private fun themeColors(): IntArray =
        if (persona() == "Yamin") intArrayOf(c("#2979FF"), c("#00E5FF"), c("#7C4DFF"))
        else intArrayOf(c("#FF3D9A"), c("#FFC857"), c("#B388FF"))

    private fun bgColors(): IntArray =
        if (persona() == "Yamin") intArrayOf(c("#04070F"), c("#0A1A3A"), c("#062B3F"))
        else intArrayOf(c("#0B0414"), c("#2A0A3A"), c("#3B0A2E"))

    private fun glass(
        radius: Int = 20,
        fill: String = "#1AFFFFFF",
        stroke: String = "#33FFFFFF"
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radius).toFloat()
        setColor(c(fill))
        setStroke(dp(1), c(stroke))
    }

    private fun accentBg(radius: Int = 24): GradientDrawable {
        val th = themeColors()
        return GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(th[0], th[2])
        ).apply { cornerRadius = dp(radius).toFloat() }
    }

    private fun stylePill(v: TextView, selected: Boolean) {
        if (selected) {
            v.background = accentBg(22)
            v.setTextColor(Color.WHITE)
            v.typeface = Typeface.DEFAULT_BOLD
        } else {
            v.background = glass(22)
            v.setTextColor(c("#B3FFFFFF"))
            v.typeface = Typeface.DEFAULT
        }
    }

    private fun makePill(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(11), dp(8), dp(11))
    }

    private fun styleEdit(e: EditText) {
        e.setTextColor(Color.WHITE)
        e.setHintTextColor(c("#80FFFFFF"))
        e.background = glass(14)
        e.setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun styleButton(b: Button) {
        b.setTextColor(Color.WHITE)
        b.stateListAnimator = null
        b.background = accentBg(24)
    }

    private fun refreshPills() {
        stylePill(pM, persona() != "Yamin")
        stylePill(pY, persona() == "Yamin")
        stylePill(lBn, lang() != "hi")
        stylePill(lHi, lang() == "hi")
    }

    private fun applyTheme() {
        val th = themeColors()
        rootLayout.background =
            GradientDrawable(GradientDrawable.Orientation.TL_BR, bgColors())
        titleView.paint.shader = LinearGradient(
            0f, 0f, dp(260).toFloat(), 0f,
            intArrayOf(th[1], Color.WHITE, th[0]), null, Shader.TileMode.CLAMP
        )
        titleView.invalidate()
        taglineView.setTextColor(th[1])
        orb.setPalette(th)
        refreshPills()
        styleButton(saveButton)
        sendBtn.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(th[0], th[2])
        ).apply { shape = GradientDrawable.OVAL }
    }

    private fun buildUi() {
        val match = LinearLayout.LayoutParams.MATCH_PARENT
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(30), dp(18), dp(30))
        }
        scrollView = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(c("#07040F"))
        }
        scrollView.addView(
            rootLayout,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        setContentView(scrollView)

        // ওপরের অংশ: নাম ও সেটিংস বাটন
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleView = TextView(this).apply {
            text = "মাইশা ও ইয়ামিন"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        taglineView = TextView(this).apply {
            text = "✦  তোমার নিজের এআই বন্ধু  ✦"
            textSize = 12f
            letterSpacing = 0.1f
        }
        titleBox.addView(titleView)
        titleBox.addView(taglineView)
        val gear = TextView(this).apply {
            text = "⚙"
            textSize = 26f
            setTextColor(c("#CCFFFFFF"))
            setPadding(dp(12), dp(6), dp(4), dp(6))
        }
        header.addView(titleBox, LinearLayout.LayoutParams(0, wrap, 1f))
        header.addView(gear)
        rootLayout.addView(header)

        fun pillLp() = LinearLayout.LayoutParams(0, wrap, 1f).apply {
            setMargins(dp(4), 0, dp(4), 0)
        }

        // কে কথা বলবে
        pM = makePill("♀  মাইশা")
        pY = makePill("♂  ইয়ামিন")
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(pM, pillLp())
        row1.addView(pY, pillLp())
        rootLayout.addView(
            row1,
            LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(18) }
        )

        // ভাষা
        lBn = makePill("বাংলা")
        lHi = makePill("हिन्दी")
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(lBn, pillLp())
        row2.addView(lHi, pillLp())
        rootLayout.addView(
            row2,
            LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(8) }
        )

        pM.setOnClickListener {
            prefs.edit().putString("persona", "Maisha").apply()
            applyTtsLanguage()
            applyTheme()
            setStatus(idleText)
        }
        pY.setOnClickListener {
            prefs.edit().putString("persona", "Yamin").apply()
            applyTtsLanguage()
            applyTheme()
            setStatus(idleText)
        }
        lBn.setOnClickListener {
            prefs.edit().putString("lang", "bn").apply()
            refreshPills()
            if (!applyTtsLanguage()) {
                setStatus("ফোনে এই ভাষার ভয়েস নেই। Settings > Text-to-speech থেকে Google ভয়েস ডাউনলোড করুন।")
            } else {
                setStatus(idleText)
            }
        }
        lHi.setOnClickListener {
            prefs.edit().putString("lang", "hi").apply()
            refreshPills()
            if (!applyTtsLanguage()) {
                setStatus("ফোনে এই ভাষার ভয়েস নেই। Settings > Text-to-speech থেকে Google ভয়েস ডাউনলোড করুন।")
            } else {
                setStatus(idleText)
            }
        }

        // সেটিংস কার্ড (বয়স, key, মডেল)
        val settingsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(22)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            visibility = if (apiKey().isEmpty()) View.VISIBLE else View.GONE
        }
        val settingsTitle = TextView(this).apply {
            text = "⚙  সেটিংস"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        settingsCard.addView(settingsTitle)

        val ageInput = EditText(this).apply {
            hint = "বয়স (১৮ থেকে ৮০)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(age())
        }
        styleEdit(ageInput)
        val keyInput = EditText(this).apply {
            hint = "Gemini API key এখানে বসান"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(apiKey())
        }
        styleEdit(keyInput)
        val modelInput = EditText(this).apply {
            hint = "মডেলের নাম"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(model())
        }
        styleEdit(modelInput)
        val gap = { LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(8) } }
        settingsCard.addView(ageInput, gap())
        settingsCard.addView(keyInput, gap())
        settingsCard.addView(modelInput, gap())

        saveButton = Button(this).apply { text = "সংরক্ষণ করুন" }
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
                settingsCard.visibility = View.GONE
                setStatus("সংরক্ষণ হয়েছে। এখন মাঝের গোলকে দুবার ট্যাপ করে কথা বলুন।")
            }
        }
        settingsCard.addView(saveButton, gap())
        rootLayout.addView(
            settingsCard,
            LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(14) }
        )
        gear.setOnClickListener {
            settingsCard.visibility =
                if (settingsCard.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        // জাদুর গোলক
        orb = OrbView(this)
        rootLayout.addView(
            orb,
            LinearLayout.LayoutParams(match, dp(340)).apply { topMargin = dp(8) }
        )
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleConversation()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!conversation) setStatus(idleText)
                return true
            }
        })
        orb.setOnTouchListener { _, ev ->
            detector.onTouchEvent(ev)
            true
        }

        statusView = TextView(this).apply {
            text = idleText
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(c("#E6FFFFFF"))
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        rootLayout.addView(statusView)

        // কথার বাবল
        chatBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rootLayout.addView(
            chatBox,
            LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(10) }
        )

        // লিখে প্রশ্ন করার ঘর
        typeInput = EditText(this).apply {
            hint = "অথবা এখানে লিখে বলো..."
            inputType = InputType.TYPE_CLASS_TEXT
        }
        styleEdit(typeInput)
        sendBtn = TextView(this).apply {
            text = "➤"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        sendBtn.setOnClickListener {
            val t = typeInput.text.toString().trim()
            if (t.isNotEmpty() && !busy) {
                typeInput.setText("")
                ask(t)
            }
        }
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        inputRow.addView(typeInput, LinearLayout.LayoutParams(0, wrap, 1f))
        inputRow.addView(
            sendBtn,
            LinearLayout.LayoutParams(dp(50), dp(50)).apply { leftMargin = dp(10) }
        )
        rootLayout.addView(
            inputRow,
            LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(12) }
        )

        applyTheme()
    }

    private fun addBubble(text: String, fromUser: Boolean) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxWidth = dp(290)
            background = if (fromUser) accentBg(18) else glass(18)
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(8)
            gravity = if (fromUser) Gravity.END else Gravity.START
        }
        chatBox.addView(tv, lp)
        if (chatBox.childCount > 30) chatBox.removeViewAt(0)
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setStatus(s: String) {
        statusView.text = s
    }

    private fun appendLog(s: String) {
        if (s.startsWith("আপনি: ")) {
            addBubble(s.removePrefix("আপনি: "), true)
        } else {
            val i = s.indexOf(": ")
            addBubble(if (i >= 0) s.substring(i + 2) else s, false)
        }
    }

    // ---------- টানা কথোপকথন ----------

    private fun toggleConversation() {
        if (conversation) {
            stopConversation("বন্ধ করলাম। আবার চালু করতে দুবার ট্যাপ করুন।")
        } else {
            startConversation()
        }
    }

    private fun startConversation() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus("এই ফোনে ভয়েস শোনার সার্ভিস পাওয়া যায়নি। Google অ্যাপ আপডেট করুন, অথবা লিখে প্রশ্ন করুন।")
            return
        }
        conversation = true
        silentCount = 0
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startListening()
    }

    private fun stopConversation(msg: String) {
        conversation = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        tts?.stop()
        busy = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        orb.mode = OrbView.Mode.IDLE
        setStatus(msg)
    }

    private fun afterSpeech() {
        if (conversation) {
            handler.postDelayed({
                if (conversation && !busy) startListening()
            }, 500)
        } else {
            orb.mode = OrbView.Mode.IDLE
            setStatus(idleText)
        }
    }

    private fun isStopCommand(t: String): Boolean =
        hasAny(
            t,
            listOf(
                "থামো", "থামুন", "কথা বন্ধ", "বাই", "bye", "stop", "চুপ",
                "रुको", "चुप", "बात बंद", "अलविदा"
            )
        )

    // ---------- কথা শোনা ----------

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            orb.pushLevel(rmsdB)
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            busy = false
            if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            ) {
                if (conversation) {
                    silentCount++
                    if (silentCount < 3) {
                        setStatus("শুনছি... বলুন")
                        handler.postDelayed({
                            if (conversation && !busy) startListening()
                        }, 400)
                    } else {
                        stopConversation("অনেকক্ষণ কিছু শুনিনি, বন্ধ করলাম। আবার দুবার ট্যাপ করুন।")
                    }
                } else {
                    setStatus("কিছু শুনতে পাইনি। আবার দুবার ট্যাপ করুন।")
                }
                return
            }
            val msg = when (error) {
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ইন্টারনেটে সমস্যা। নেট চেক করুন।"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "মাইক্রোফোনের অনুমতি দিন।"
                else -> "শোনার সময় সমস্যা হয়েছে (কোড $error)।"
            }
            stopConversation(msg)
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (text.isNullOrBlank()) {
                busy = false
                if (conversation) {
                    silentCount++
                    if (silentCount < 3) {
                        handler.postDelayed({
                            if (conversation && !busy) startListening()
                        }, 400)
                    } else {
                        stopConversation("অনেকক্ষণ কিছু শুনিনি, বন্ধ করলাম। আবার দুবার ট্যাপ করুন।")
                    }
                } else {
                    setStatus("কিছু শুনতে পাইনি। আবার দুবার ট্যাপ করুন।")
                }
            } else {
                silentCount = 0
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
        tts?.stop()
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (lang() == "hi") "hi-IN" else "bn-BD")
        }
        busy = true
        setStatus("শুনছি... বলুন")
        orb.mode = OrbView.Mode.LISTENING
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

    // ---------- ফোনের কমান্ড (টর্চ, ব্যাটারি, অ্যাপ খোলা) ----------

    private fun hasAny(t: String, words: List<String>): Boolean = words.any { t.contains(it) }

    private fun setTorch(on: Boolean): Boolean {
        return try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { cid ->
                val ch = cm.getCameraCharacteristics(cid)
                ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: return false
            cm.setTorchMode(id, on)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun batteryReply(): String {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val p = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val st = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING
        return if (lang() == "hi") {
            "बैटरी अभी $p प्रतिशत है" + (if (charging) " और चार्ज हो रही है।" else "।")
        } else {
            "ব্যাটারি এখন $p শতাংশ আছে" + (if (charging) " আর চার্জ হচ্ছে।" else "।")
        }
    }

    private val appAliases: List<Pair<List<String>, String>> = listOf(
        listOf("ইউটিউব", "youtube", "यूट्यूब") to "youtube",
        listOf("ফেসবুক", "facebook", "फेसबुक") to "facebook",
        listOf("মেসেঞ্জার", "ম্যাসেঞ্জার", "messenger", "मैसेंजर") to "messenger",
        listOf("হোয়াটসঅ্যাপ", "whatsapp", "व्हाट्सएप", "वॉट्सऐप") to "whatsapp",
        listOf("ক্রোম", "chrome", "क्रोम") to "chrome",
        listOf("গুগল", "google", "गूगल") to "google",
        listOf("ক্যামেরা", "camera", "कैमरा") to "camera",
        listOf("ক্যালকুলেটর", "calculator", "कैलकुलेटर") to "calculator",
        listOf("সেটিংস", "সেটিং", "settings", "सेटिंग") to "settings",
        listOf("গ্যালারি", "gallery", "गैलरी") to "gallery",
        listOf("ইনস্টাগ্রাম", "instagram", "इंस्टाग्राम") to "instagram",
        listOf("টেলিগ্রাম", "telegram", "टेलीग्राम") to "telegram",
        listOf("জিমেইল", "gmail", "जीमेल") to "gmail",
        listOf("ম্যাপ", "maps", "मैप") to "maps",
        listOf("টিকটক", "tiktok") to "tiktok",
        listOf("ইমো", "imo") to "imo",
        listOf("বিকাশ", "bkash") to "bkash",
        listOf("নগদ", "nagad") to "nagad"
    )

    private fun openApp(t: String): String? {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
        var chosen: ResolveInfo? = null

        for ((keys, target) in appAliases) {
            if (keys.any { t.contains(it) }) {
                chosen = apps.firstOrNull {
                    it.loadLabel(pm).toString().lowercase(Locale.ROOT).contains(target)
                }
                if (chosen != null) break
            }
        }
        if (chosen == null) {
            chosen = apps.firstOrNull {
                val l = it.loadLabel(pm).toString().lowercase(Locale.ROOT)
                l.length >= 3 && t.contains(l)
            }
        }
        val app = chosen ?: return null
        val label = app.loadLabel(pm).toString()
        return try {
            val i = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(app.activityInfo.packageName, app.activityInfo.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            if (lang() == "hi") "$label खुल रहा है।" else "$label খুলছি।"
        } catch (e: Exception) {
            null
        }
    }

    private fun handleLocalCommand(raw: String): String? {
        val t = raw.lowercase(Locale.ROOT)
        val hi = lang() == "hi"

        // টর্চ
        if (hasAny(t, listOf("টর্চ", "torch", "flashlight", "flash light", "टॉर्च", "टार्च", "फ्लैशलाइट"))) {
            val off = hasAny(t, listOf("নিভ", "বন্ধ", "অফ", "off", "बंद", "बुझ", "ऑफ"))
            val on = hasAny(t, listOf("জ্বাল", "জ্বেল", "চালু", "অন", "on", "जला", "चालू", "ऑन"))
            if (on != off) {
                val ok = setTorch(on)
                return if (!ok) {
                    if (hi) "इस फोन पर टॉर्च नहीं चल सका।" else "এই ফোনে টর্চ চালাতে পারলাম না।"
                } else if (on) {
                    if (hi) "टॉर्च जला दी।" else "টর্চ জ্বালিয়ে দিলাম।"
                } else {
                    if (hi) "टॉर्च बंद कर दी।" else "টর্চ নিভিয়ে দিলাম।"
                }
            }
        }

        // ব্যাটারি
        if (hasAny(t, listOf("ব্যাটারি", "চার্জ", "battery", "बैटरी", "चार्ज"))) {
            return batteryReply()
        }

        // অ্যাপ খোলা
        if (hasAny(t, listOf("খোল", "ওপেন", "open", "खोल", "ओपन", "चला"))) {
            val r = openApp(t)
            if (r != null) return r
        }
        return null
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
            "and explain why. The app itself handles torch on/off, battery level and opening apps " +
            "without you. If the user asks to open an app and you are the one answering, " +
            "say you could not find that app. Calls and messages are not supported yet, so " +
            "if asked, say honestly that this feature is coming soon. " +
            "Never pretend you did something you did not do."
    }

    private fun ask(userText: String) {
        appendLog("আপনি: $userText")
        val name = displayName()
        val t = userText.lowercase(Locale.ROOT)

        // থামার কথা বললে কথোপকথন বন্ধ
        if (isStopCommand(t)) {
            val bye = if (lang() == "hi") "ठीक है, फिर बात करते हैं।" else "ঠিক আছে, পরে কথা হবে।"
            appendLog("$name: $bye")
            conversation = false
            busy = false
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            orb.mode = OrbView.Mode.IDLE
            speak(bye)
            return
        }

        val local = handleLocalCommand(userText)
        if (local != null) {
            appendLog("$name: $local")
            busy = false
            speak(local)
            return
        }
        val key = apiKey()
        if (key.isEmpty()) {
            busy = false
            stopConversation("আগে ওপরের সেটিংসে (⚙) Gemini API key বসিয়ে সংরক্ষণ করুন।")
            return
        }
        busy = true
        setStatus("ভাবছি...")
        orb.mode = OrbView.Mode.THINKING
        Thread {
            try {
                var reply = ""
                var attempt = 0
                while (true) {
                    try {
                        reply = callGemini(userText, key)
                        break
                    } catch (e: Exception) {
                        val busyServer = (e.message ?: "").contains("কোড 503")
                        attempt++
                        if (busyServer && attempt < 3) {
                            Thread.sleep(2500)
                        } else {
                            throw e
                        }
                    }
                }
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
                    stopConversation("ইন্টারনেট সংযোগ চেক করুন।")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    stopConversation(e.message ?: "কিছু একটা সমস্যা হয়েছে।")
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
                    runOnUiThread { afterSpeech() }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    runOnUiThread { afterSpeech() }
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
            afterSpeech()
            return
        }
        setStatus("বলছি...")
        orb.mode = OrbView.Mode.SPEAKING
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u1")
    }
}
