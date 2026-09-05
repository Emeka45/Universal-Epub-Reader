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
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.jsoup.Jsoup

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var titleView: TextView
    private lateinit var search: EditText
    private var bookDir: File? = null
    private var chapters = emptyList<String>()
    private var chapterIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (savedInstanceState == null) openPicker()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(8, 6, 8, 6) }
        titleView = TextView(this).apply { text = "Universal EPUB Reader"; textSize = 17f; setPadding(6, 0, 6, 0) }
        val open = Button(this).apply { text = "Open"; setOnClickListener { openPicker() } }
        val previous = Button(this).apply { text = "‹"; setOnClickListener { showChapter(chapterIndex - 1) } }
        val next = Button(this).apply { text = "›"; setOnClickListener { showChapter(chapterIndex + 1) } }
        search = EditText(this).apply {
            hint = "Find"
            setSingleLine(true)
            setOnEditorActionListener { _, _, _ -> find(text.toString()); true }
        }
        bar.addView(open, LinearLayout.LayoutParams(72.dp(), 50.dp()))
        bar.addView(previous, LinearLayout.LayoutParams(48.dp(), 50.dp()))
        bar.addView(next, LinearLayout.LayoutParams(48.dp(), 50.dp()))
        bar.addView(titleView, LinearLayout.LayoutParams(0, 50.dp(), 1f))
        bar.addView(search, LinearLayout.LayoutParams(130.dp(), 50.dp()))

        webView = WebView(this).apply {
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = false
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = WebViewClient()
        }
        root.addView(bar)
        root.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun openPicker() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, REQUEST_OPEN)
    }

    @Deprecated("Deprecated in Android API 33")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OPEN && resultCode == RESULT_OK) data?.data?.let { loadEpub(it) }
    }

    private fun loadEpub(uri: Uri) {
        titleView.text = "Opening…"
        Thread {
            try {
                val workDir = File(cacheDir, "epub_${System.currentTimeMillis()}").apply { mkdirs() }
                val epub = File(workDir, "book.epub")
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Unable to read EPUB" }
                    FileOutputStream(epub).use { output -> input.copyTo(output) }
                }
                ZipFile(epub).use { zip ->
                    extractZip(zip, workDir)
                    val containerFile = File(workDir, "META-INF/container.xml")
                    require(containerFile.exists()) { "Invalid EPUB: container.xml missing" }
                    val containerDoc = Jsoup.parse(containerFile, "UTF-8")
                    val opfPath = containerDoc.select("rootfile").firstOrNull()?.attr("full-path") ?: error("EPUB package file missing")
                    val opfFile = File(workDir, opfPath)
                    require(opfFile.exists()) { "EPUB package file missing" }
                    val opf = Jsoup.parse(opfFile, "UTF-8")
                    val opfRoot = opfFile.parentFile ?: workDir
                    val manifest = opf.select("manifest item").associate { it.attr("id") to it.attr("href") }
                    val spineFiles = opf.select("spine itemref").mapNotNull { ref ->
                        manifest[ref.attr("idref")]?.let { File(opfRoot, decode(it)).canonicalFile }
                    }.filter { it.exists() }
                    require(spineFiles.isNotEmpty()) { "EPUB has no readable chapters" }
                    val chapterHtml = spineFiles.mapIndexed { index, file ->
                        val doc = Jsoup.parse(file, "UTF-8")
                        doc.select("img, link, source, video, audio").forEach { element ->
                            val attr = when (element.tagName()) { "link" -> "href" else -> "src" }
                            val value = element.attr(attr)
                            if (value.isNotBlank() && !value.startsWith("http://") && !value.startsWith("https://") && !value.startsWith("data:")) {
                                val target = File(file.parentFile, decode(value.substringBefore('#')))
                                if (target.exists()) element.attr(attr, target.toURI().toString())
                            }
                        }
                        "<section id='chapter$index'><h2>${index + 1}</h2>${doc.body().html()}</section>"
                    }
                    chapters = spineFiles.map { it.absolutePath }
                    val html = """
                        <html><head><meta name='viewport' content='width=device-width,initial-scale=1'>
                        <style>body{font-family:sans-serif;line-height:1.75;padding:24px;max-width:900px;margin:auto}img,video{max-width:100%;height:auto}h1,h2,h3{line-height:1.3}section{margin-bottom:36px}a{color:#1565c0}</style></head>
                        <body>${chapterHtml.joinToString("\n")}</body></html>
                    """.trimIndent()
                    bookDir = workDir
                    chapterIndex = 0
                    runOnUiThread {
                        titleView.text = uri.lastPathSegment?.substringAfterLast('/') ?: "EPUB"
                        webView.loadDataWithBaseURL(workDir.toURI().toString(), html, "text/html", "UTF-8", null)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    titleView.text = "EPUB error"
                    Toast.makeText(this, "Could not open EPUB: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun extractZip(zip: ZipFile, destination: File) {
        val base = destination.canonicalFile
        zip.entries().asSequence().forEach { entry: ZipEntry ->
            val target = File(destination, entry.name).canonicalFile
            require(target.path == base.path || target.path.startsWith(base.path + File.separator)) { "Unsafe EPUB path" }
            if (entry.isDirectory) target.mkdirs() else {
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> FileOutputStream(target).use { output -> input.copyTo(output) } }
            }
        }
    }

    private fun showChapter(index: Int) {
        if (index !in chapters.indices) return
        chapterIndex = index
        webView.evaluateJavascript("document.getElementById('chapter$index')?.scrollIntoView({behavior:'smooth'});", null)
    }

    private fun find(query: String) { if (query.isNotBlank()) webView.findAllAsync(query) }
    private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")
    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
    companion object { private const val REQUEST_OPEN = 100 }
}
