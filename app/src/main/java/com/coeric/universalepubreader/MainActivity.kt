package com.coeric.universalepubreader

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import org.jsoup.Jsoup

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var titleView: TextView
    private lateinit var search: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (savedInstanceState == null) openPicker()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10, 8, 10, 8)
        }
        titleView = TextView(this).apply {
            text = "Universal EPUB Reader"
            textSize = 18f
            setPadding(8, 0, 8, 0)
        }
        val open = Button(this).apply {
            text = "Open"
            setOnClickListener { openPicker() }
        }
        search = EditText(this).apply {
            hint = "Find"
            singleLine = true
            setOnEditorActionListener { _, _, _ -> find(text.toString()); true }
        }
        bar.addView(titleView, LinearLayout.LayoutParams(0, 52.dp(), 1f))
        bar.addView(open, LinearLayout.LayoutParams(80.dp(), 52.dp()))
        bar.addView(search, LinearLayout.LayoutParams(140.dp(), 52.dp()))

        webView = WebView(this).apply {
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = false
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            webViewClient = WebViewClient()
        }
        root.addView(bar)
        root.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun openPicker() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/epub+zip"
        }, 100)
    }

    @Deprecated("Deprecated in Android API 33")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK) data?.data?.let { loadEpub(it) }
    }

    private fun loadEpub(uri: Uri) {
        titleView.text = "Opening EPUB…"
        Thread {
            try {
                val epub = File(cacheDir, "book_${System.currentTimeMillis()}.epub")
                contentResolver.openInputStream(uri).use { input ->
                    FileOutputStream(epub).use { output -> input!!.copyTo(output) }
                }
                val zip = ZipFile(epub)
                val container = zip.getEntry("META-INF/container.xml") ?: error("Invalid EPUB")
                val containerDoc = Jsoup.parse(zip.getInputStream(container), "", "")
                val opfPath = containerDoc.select("rootfile").firstOrNull()?.attr("full-path")
                    ?: error("EPUB package file missing")
                val opf = Jsoup.parse(zip.getInputStream(zip.getEntry(opfPath)), "UTF-8", "")
                val rootPath = opfPath.substringBeforeLast('/', "")
                val manifest = opf.select("manifest item").associate { it.attr("id") to it.attr("href") }
                val spine = opf.select("spine itemref").mapNotNull { manifest[it.attr("idref")] }
                val html = StringBuilder("<html><head><meta name='viewport' content='width=device-width,initial-scale=1'><style>body{font-family:sans-serif;line-height:1.75;padding:22px;max-width:850px;margin:auto}img{max-width:100%;height:auto}h1,h2,h3{line-height:1.3}</style></head><body>")
                spine.forEach { href ->
                    val path = if (rootPath.isEmpty()) href else "$rootPath/$href"
                    zip.getEntry(path)?.let { entry ->
                        val doc = Jsoup.parse(zip.getInputStream(entry), "UTF-8", "")
                        html.append(doc.body().html())
                    }
                }
                html.append("</body></html>")
                zip.close()
                epub.deleteOnExit()
                runOnUiThread {
                    titleView.text = uri.lastPathSegment?.substringAfterLast('/') ?: "EPUB"
                    webView.loadDataWithBaseURL("file://${epub.parentFile?.absolutePath}/", html.toString(), "text/html", "UTF-8", null)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    titleView.text = "EPUB error"
                    webView.loadData("<h3>Could not open EPUB</h3><p>${e.message}</p>", "text/html", "UTF-8")
                }
            }
        }.start()
    }

    private fun find(query: String) {
        if (query.isNotBlank()) webView.findAllAsync(query)
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
}
