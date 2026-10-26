package com.varfit.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.varfit.core.CsvImport
import com.varfit.core.Dataset
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class MainActivity : Activity() {

    companion object {
        const val REQ_OPEN = 11
        const val REQ_SAVE = 12
    }

    /** Software string written into AAVSO files. */
    val software: String by lazy {
        val v = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "?"
        "VarFit $v (Android)"
    }

    private lateinit var content: FrameLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var busyBar: LinearLayout
    private lateinit var busyLabel: TextView
    private lateinit var busyProgress: ProgressBar
    private val tabNames = listOf("Data", "Target", "Fit", "Export")
    private val scrollPos = IntArray(4)
    private var scroll: ScrollView? = null
    var page = 0
        private set

    /** Reads the current page's form fields into the state. */
    var commit: () -> Unit = {}

    private val executor = Executors.newSingleThreadExecutor()
    @Volatile var cancelRequested = false
    @Volatile var busy = false
    private var pendingSave: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Pal.init(this)
        Prefs.load(this)
        // Draw edge-to-edge on every Android version and pad by the system-bar insets ourselves.
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = Pal.surface
            var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            if (!Pal.dark) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = flags
        }
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)

        val root = vbox()
        root.setBackgroundColor(Pal.surface)

        val header = vbox().apply {
            setBackgroundColor(Pal.surface)
            setPadding(dp(16), dp(10), dp(16), 0)
        }
        header.addView(hbox().apply {
            addView(textView("VarFit", 20f, Pal.text, bold = true))
            addView(textView("  light-curve fitter", 13f, Pal.muted))
        })
        tabBar = hbox().apply { setPadding(0, dp(6), 0, 0) }
        header.addView(tabBar)
        root.addView(header)

        busyBar = vbox().apply {
            setBackgroundColor(Pal.surface2)
            setPadding(dp(16), dp(6), dp(16), dp(6))
            visibility = View.GONE
        }
        val busyRow = hbox()
        busyLabel = textView("", 13f, Pal.text)
        busyRow.addView(busyLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        busyRow.addView(button("Cancel") { cancelRequested = true; busyLabel.text = "Cancelling…" })
        busyBar.addView(busyRow)
        busyProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        busyBar.addView(busyProgress)
        root.addView(busyBar)

        content = FrameLayout(this)
        content.setBackgroundColor(Pal.bg)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        applyInsets(root)

        buildTabs()
        handleIncomingIntent(intent)
        showPage(0)
    }

    private fun applyInsets(root: View) {
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
    }

    private fun buildTabs() {
        tabBar.removeAllViews()
        for ((i, name) in tabNames.withIndex()) {
            val sel = i == page
            val tv = TextView(this).apply {
                text = name
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(if (sel) Pal.accent else Pal.muted)
                typeface = if (sel) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { if (!busy) showPage(i) }
            }
            val col = vbox()
            col.addView(tv)
            col.addView(View(this).apply { setBackgroundColor(if (sel) Pal.accent else 0) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
            tabBar.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    /** Builds (or rebuilds) page [i]. The current page's form is committed first. */
    fun showPage(i: Int, keepScroll: Boolean = false) {
        runCatching { commit() }
        scroll?.let { scrollPos[page] = it.scrollY }
        val target = if (keepScroll || i == page) scrollPos[i] else scrollPos[i]
        page = i
        commit = {}
        buildTabs()
        val inner = vbox(14)
        when (i) {
            0 -> buildDataPage(inner)
            1 -> buildTargetPage(inner)
            2 -> buildFitPage(inner)
            3 -> buildExportPage(inner)
        }
        val sv = ScrollView(this).apply {
            isFillViewport = true
            addView(inner)
        }
        content.removeAllViews()
        content.addView(sv)
        scroll = sv
        sv.post { sv.scrollTo(0, target) }
    }

    fun refresh() = showPage(page, keepScroll = true)

    // ------------------------------------------------------------------ background tasks

    fun <T> runTask(label: String, work: (progress: (Double) -> Unit, cancel: () -> Boolean) -> T, done: (T) -> Unit) {
        if (busy) { toast("Still working…"); return }
        runCatching { commit() }
        busy = true
        cancelRequested = false
        busyLabel.text = label
        busyProgress.progress = 0
        busyBar.visibility = View.VISIBLE
        var lastP = -1
        val progress: (Double) -> Unit = { p ->
            val ip = (p * 1000).toInt().coerceIn(0, 1000)
            if (ip - lastP >= 10 || ip == 1000) {
                lastP = ip
                runOnUiThread { busyProgress.progress = ip }
            }
        }
        executor.execute {
            try {
                val r = work(progress) { cancelRequested }
                runOnUiThread {
                    busy = false
                    busyBar.visibility = View.GONE
                    if (cancelRequested) toast("Cancelled")
                    done(r)
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    busy = false
                    busyBar.visibility = View.GONE
                    message("Error", e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    // ------------------------------------------------------------------ dialogs / toasts

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    fun message(title: String, msg: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
    }

    fun choose(title: String, items: List<String>, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title)
            .setItems(items.toTypedArray()) { _, which -> onPick(which) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ files

    fun openCsv() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/csv", "application/vnd.ms-excel", "application/octet-stream"))
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_OPEN)
    }

    fun saveText(fileName: String, text: String) = saveBytes(fileName, "text/plain", text.toByteArray(Charsets.UTF_8))

    fun savePng(fileName: String, bmp: Bitmap) {
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        saveBytes(fileName, "image/png", bos.toByteArray())
    }

    private fun saveBytes(fileName: String, mime: String, bytes: ByteArray) {
        pendingSave = bytes
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, fileName)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_SAVE)
    }

    fun shareText(subject: String, text: String) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(i, "Share"))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) { pendingSave = null; return }
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_OPEN -> loadUri(uri)
            REQ_SAVE -> {
                val bytes = pendingSave ?: return
                try {
                    val os = runCatching { contentResolver.openOutputStream(uri, "wt") }.getOrNull()
                        ?: contentResolver.openOutputStream(uri)
                    os?.use { it.write(bytes) } ?: throw IllegalStateException("Cannot open the file for writing")
                    toast("Saved")
                } catch (e: Exception) {
                    message("Could not save", e.message ?: e.toString())
                }
                pendingSave = null
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        intent ?: return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
            else -> null
        }
        if (uri != null) loadUri(uri)
    }

    private fun displayName(uri: Uri): String {
        var name = uri.lastPathSegment ?: "data.csv"
        runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0) ?: name
            }
        }
        return name
    }

    private fun loadUri(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalStateException("Cannot read file")
            loadText(text, displayName(uri))
        } catch (e: Exception) {
            message("Import failed", e.message ?: e.toString())
        }
    }

    fun loadText(text: String, name: String) {
        try {
            val ds: Dataset = CsvImport.parse(text, name)
            AppState.session.load(ds)
            AppState.resetFits()
            AppState.modelId = null
            lastExport = null
            val t = AppState.session.target
            t.vsxName = null; t.auid = null; t.vsxType = null; t.magRange = null; t.catalogNote = null
            t.planetName = null; t.hostName = null
            if (page != 0) showPage(0) else refresh()
            toast("Loaded ${ds.obs.size} points of ${ds.name}")
        } catch (e: Exception) {
            message("Import failed", e.message ?: e.toString())
        }
    }

    fun loadExample() {
        try {
            val text = assets.open("example_dy_peg_seestar.csv").bufferedReader().use { it.readText() }
            loadText(text, "example_dy_peg_seestar.csv")
        } catch (e: Exception) {
            message("Example missing", e.message ?: e.toString())
        }
    }

    override fun onPause() {
        runCatching { commit() }
        Prefs.save(this)
        super.onPause()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
