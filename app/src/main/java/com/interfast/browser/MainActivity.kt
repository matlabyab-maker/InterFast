package com.interfast.browser

import android.app.*
import android.os.*
import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var address: EditText
    private lateinit var status: TextView
    private lateinit var toolbar: LinearLayout
    private lateinit var root: LinearLayout
    private val prefs by lazy { getSharedPreferences("interfast", MODE_PRIVATE) }
    private val tabs = mutableListOf<String>()
    private var currentTab = 0
    private var editing = false
    private var desktop = true
    private var imagesOn = true
    private var jsOn = true
    private var baseScale = 1f
    private var zoomFactor = 1f
    private var fontPercent = 100
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val FILE_PICK = 301
    private val handler = Handler(Looper.getMainLooper())
    private var lightAnimator: android.animation.ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(242,245,248)
        window.navigationBarColor = Color.BLACK
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        restoreTabs()
        buildUi()
        val start = tabs.getOrNull(currentTab) ?: "https://www.google.com"
        loadAddress(start)
        scheduleHourlyLight()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun button(label: String, action: () -> Unit): TextView {
        return TextView(this).apply {
            text = label; textSize = 12f; gravity = Gravity.CENTER
            setTextColor(Color.rgb(30,45,60)); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(7), dp(4), dp(7))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(229,237,246)); cornerRadius = dp(13).toFloat()
                setStroke(dp(1), Color.rgb(190,205,220))
            }
            isClickable = true; isFocusable = true
            setOnClickListener { action() }
        }
    }
    private fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, 0)
    }
    private fun addButtons(parent: LinearLayout, labels: List<Pair<String, () -> Unit>>) {
        labels.forEach { (name, fn) ->
            val b = button(name, fn)
            parent.addView(b, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                setMargins(0, 0, 0, 0)
            })
        }
    }

    private fun buildUi() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        val top = row()
        address = EditText(this).apply {
            singleLine = true; textSize = 14f; hint = "Enter URL or search"
            setPadding(dp(10), 0, dp(8), 0)
            setTextColor(Color.BLACK)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> loadAddress(text.toString()); hideKeyboard(); true }
        }
        top.addView(address, LinearLayout.LayoutParams(0, dp(44), 1f))
        top.addView(button("GO") { loadAddress(address.text.toString()); hideKeyboard() }, LinearLayout.LayoutParams(dp(52), dp(40)))
        top.addView(button("TABS") { showTabs() }, LinearLayout.LayoutParams(dp(58), dp(40)))
        root.addView(top)

        toolbar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0,0,0,0) }
        val r1 = row()
        addButtons(r1, listOf(
            "Send" to { shareCurrent() }, "File" to { chooseFile() }, "Past" to { pasteAddress() },
            "Select All" to { runJs("document.body && document.body.focus(); document.execCommand('selectAll')") },
            "Copy" to { runJs("document.execCommand('copy')") }, "Setup" to { showSetup() }
        ))
        val r2 = row()
        addButtons(r2, listOf(
            "Up" to { runJs("window.scrollBy(0,-Math.max(250,innerHeight*0.75))") },
            "Down" to { runJs("window.scrollBy(0,Math.max(250,innerHeight*0.75))") },
            "Back" to { if (web.canGoBack()) web.goBack() },
            "Forward" to { if (web.canGoForward()) web.goForward() },
            "Full" to { toggleFullScreen() },
            "Edit Page" to { toggleEdit() }
        ))
        toolbar.addView(r1); toolbar.addView(r2)
        root.addView(toolbar)

        web = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = jsOn
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = imagesOn
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = false
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.allowFileAccess = false
            settings.defaultTextEncodingName = "UTF-8"
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    url?.let { address.setText(it) }; status.text = "Loading…"
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    url?.let { address.setText(it); saveCurrentTab(it) }
                    status.text = "Ready"
                    applyFontSize()
                    if (desktop) applyDesktop()
                }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val u = request?.url?.toString() ?: return false
                    if (u.startsWith("mailto:") || u.startsWith("tel:")) {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }; return true
                    }
                    return false
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(w: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                    fileCallback?.onReceiveValue(null); fileCallback = callback
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) }
                    startActivityForResult(Intent.createChooser(intent, "Select file"), FILE_PICK)
                    return true
                }
            }
            setDownloadListener { url, ua, disposition, mime, _ -> download(url, ua, disposition, mime) }
        }
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        status = TextView(this).apply {
            text = "Ready"; textSize = 10f; setTextColor(Color.GRAY); setPadding(dp(6),0,dp(6),0)
        }
        root.addView(status, LinearLayout.LayoutParams(-1, dp(17)))
        setContentView(root)
        installPinchZoom()
    }

    private fun normalize(input: String): String {
        val s = input.trim()
        if (s.isEmpty()) return "https://www.google.com"
        if (s.contains(" ") || (!s.contains(".") && !s.startsWith("http"))) {
            return "https://www.google.com/search?q=" + Uri.encode(s)
        }
        return if (s.startsWith("http://") || s.startsWith("https://")) s else "https://$s"
    }
    private fun loadAddress(s: String) { editing = false; address.setText(normalize(s)); web.loadUrl(normalize(s)) }
    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(address.windowToken, 0)
        address.clearFocus()
    }
    private fun runJs(script: String) { web.evaluateJavascript(script, null) }

    private fun toggleEdit() {
        editing = !editing
        if (editing) {
            runJs("document.designMode='on'; document.body && (document.body.contentEditable='true');")
            (toolbar.getChildAt(1) as LinearLayout).getChildAt(5).let { (it as TextView).text = "Normal Page" }
            status.text = "Temporary edit mode — changes are not saved"
        } else {
            val url = web.url ?: "https://www.google.com"
            runJs("document.designMode='off'; document.body && (document.body.contentEditable='false');")
            (toolbar.getChildAt(1) as LinearLayout).getChildAt(5).let { (it as TextView).text = "Edit Page" }
            web.loadUrl(url)
            status.text = "Original page restored"
        }
    }

    private fun installPinchZoom() {
        val detector = android.view.ScaleGestureDetector(this, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
                zoomFactor = (zoomFactor * d.scaleFactor).coerceIn(0.4f, 1.6f)
                web.evaluateJavascript("document.documentElement.style.zoom='${zoomFactor}'", null)
                return true
            }
        })
        web.setOnTouchListener { _, event -> detector.onTouchEvent(event); false }
    }
    private fun resetZoom() { zoomFactor = 1f; runJs("document.documentElement.style.zoom='1'") }
    private fun applyFontSize() { runJs("document.documentElement.style.fontSize='${fontPercent}%';") }
    private fun applyDesktop() {
        val ua = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        web.settings.userAgentString = ua
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = false
    }
    private fun applyMobile() {
        web.settings.userAgentString = WebSettings.getDefaultUserAgent(this)
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = true
    }
    private fun showSetup() {
        val options = arrayOf("Windows layout", "Mobile layout", "Text Size", "Reset page zoom", "Images: ${if(imagesOn) "ON" else "OFF"}", "JavaScript: ${if(jsOn) "ON" else "OFF"}", "Downloads", "Reload page")
        AlertDialog.Builder(this).setTitle("InterFast Setup").setItems(options) { _, which ->
            when(which) {
                0 -> { desktop = true; applyDesktop(); web.reload() }
                1 -> { desktop = false; applyMobile(); web.reload() }
                2 -> chooseFontSize()
                3 -> resetZoom()
                4 -> { imagesOn = !imagesOn; web.settings.loadsImagesAutomatically = imagesOn; web.reload() }
                5 -> { jsOn = !jsOn; web.settings.javaScriptEnabled = jsOn; web.reload() }
                6 -> startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
                7 -> web.reload()
            }
            prefs.edit().putBoolean("desktop", desktop).putBoolean("images", imagesOn).putBoolean("js", jsOn).apply()
        }.setNegativeButton("Close", null).show()
    }
    private fun chooseFontSize() {
        val seek = SeekBar(this).apply { max = 180; progress = fontPercent - 60 }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20),dp(12),dp(20),dp(4)) }
        val label = TextView(this).apply { text = "Text Size: $fontPercent%" }
        seek.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { fontPercent = p + 60; label.text = "Text Size: $fontPercent%"; applyFontSize() }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        box.addView(label); box.addView(seek)
        AlertDialog.Builder(this).setTitle("Text Size").setView(box).setPositiveButton("OK", null).setNegativeButton("Reset") {_,_-> fontPercent=100; applyFontSize()}.show()
    }
    private fun download(url: String, ua: String, disposition: String, mime: String) {
        try {
            val name = URLUtil.guessFileName(url, disposition, mime)
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(name); setDescription("InterFast download")
                setMimeType(mime)
                addRequestHeader("User-Agent", ua)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedOverMetered(true); setAllowedOverRoaming(true)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "InterFast/$name")
            }
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            Toast.makeText(this, "Download started: $name", Toast.LENGTH_LONG).show()
        } catch (e: Exception) { Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show() }
    }
    private fun chooseFile() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) }
        startActivityForResult(Intent.createChooser(i, "Choose file"), FILE_PICK)
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_PICK) {
            val uri = if (resultCode == RESULT_OK) data?.data else null
            fileCallback?.onReceiveValue(if (uri == null) null else arrayOf(uri)); fileCallback = null
        }
    }
    private fun pasteAddress() {
        val clip = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        if (clip.hasPrimaryClip()) address.setText(clip.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: "")
        address.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(address, InputMethodManager.SHOW_IMPLICIT)
    }
    private fun shareCurrent() {
        val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, web.url ?: "") }
        startActivity(Intent.createChooser(i, "Send link"))
    }
    private fun toggleFullScreen() {
        val flags = window.decorView.systemUiVisibility
        val hide = flags and View.SYSTEM_UI_FLAG_FULLSCREEN == 0
        window.decorView.systemUiVisibility = if (hide)
            (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
    }
    private fun restoreTabs() {
        desktop = prefs.getBoolean("desktop", true); imagesOn = prefs.getBoolean("images", true); jsOn = prefs.getBoolean("js", true)
        fontPercent = prefs.getInt("font", 100)
        val raw = prefs.getString("tabs", null)
        if (raw != null) runCatching {
            val arr = JSONArray(raw); for (i in 0 until arr.length()) tabs.add(arr.getString(i))
            currentTab = prefs.getInt("current", 0).coerceIn(0, max(0,tabs.size-1))
        }
        if (tabs.isEmpty()) tabs.add("https://www.google.com")
    }
    private fun persistTabs() {
        val arr = JSONArray(); tabs.forEach { arr.put(it) }
        prefs.edit().putString("tabs", arr.toString()).putInt("current", currentTab).putInt("font", fontPercent).apply()
    }
    private fun saveCurrentTab(url: String) {
        if (tabs.isEmpty()) tabs.add(url) else tabs[currentTab.coerceIn(0,tabs.lastIndex)] = url
        persistTabs()
    }
    private fun showTabs() {
        val labels = tabs.mapIndexed { i, u -> "${i+1}. $u" }.toMutableList()
        labels.add("+ New tab")
        AlertDialog.Builder(this).setTitle("Open tabs — tap to switch").setItems(labels.toTypedArray()) { _, which ->
            if (which == tabs.size) {
                tabs.add("https://www.google.com"); currentTab = tabs.lastIndex; persistTabs(); web.loadUrl(tabs[currentTab])
            } else {
                currentTab = which; persistTabs(); web.loadUrl(tabs[currentTab])
            }
        }.setNeutralButton("Close current") { _, _ ->
            if (tabs.size > 1) { tabs.removeAt(currentTab); currentTab = currentTab.coerceAtMost(tabs.lastIndex); persistTabs(); web.loadUrl(tabs[currentTab]) }
            else { tabs[0] = "https://www.google.com"; web.loadUrl(tabs[0]) }
        }.setNegativeButton("Done", null).show()
    }
    private fun scheduleHourlyLight() {
        val now = java.util.Calendar.getInstance()
        val delay = (60 - now.get(java.util.Calendar.MINUTE)) * 60_000L - now.get(java.util.Calendar.SECOND)*1000L
        handler.postDelayed({
            val anim = android.animation.ValueAnimator.ofFloat(0.35f, 1f, 0.35f).apply {
                duration = 1200; repeatCount = 24
                addUpdateListener { v ->
                    val alpha = v.animatedValue as Float
                    toolbar.alpha = alpha
                }
            }
            lightAnimator = anim; anim.start(); handler.postDelayed({ toolbar.alpha=1f }, 60_000L)
            scheduleHourlyLight()
        }, max(1000L, delay))
    }
    override fun onPause() { super.onPause(); if (::web.isInitialized) saveCurrentTab(web.url ?: tabs[currentTab]); }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); lightAnimator?.cancel(); super.onDestroy() }
    override fun onBackPressed() {
        if (editing) { toggleEdit(); return }
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
