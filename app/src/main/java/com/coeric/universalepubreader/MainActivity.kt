package com.coeric.universalepubreader

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.jsoup.Jsoup

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var titleView: TextView
    private lateinit var search: EditText
    private var bookDir: File? = null
    private var chapters = emptyList<String>()
    private var chapterTitles = emptyList<String>()
    private var chapterIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (savedInstanceState == null) openPicker()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 6, 8, 6)
        }
        titleView = TextView(this).apply {
            text = "Universal EPUB Reader"
            textSize = 17f
            setPadding(6, 0, 6, 0)
        }
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
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = handleChapterLink(request.url)
                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = handleChapterLink(Uri.parse(url))
            }
        }
        root.addView(bar)
        root.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun openPicker() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/epub+zip"
        }, REQUEST_OPEN)
    }

    @Deprecated("Deprecated in Android API 33")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OPEN && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                if ((data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                    try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
                }
                loadEpub(uri)
            }
        }
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

                val parsedChapters: List<String>
                val parsedTitles: List<String>
                ZipFile(epub).use { zip ->
                    extractZip(zip, workDir)
                    val containerFile = File(workDir, "META-INF/container.xml")
                    require(containerFile.exists()) { "Invalid EPUB: container.xml missing" }
                    val containerDoc = Jsoup.parse(containerFile, "UTF-8")
                    val opfPath = containerDoc.select("rootfile").firstOrNull()?.attr("full-path")?.takeIf { it.isNotBlank() }
                        ?: error("EPUB package file missing")
                    val opfFile = safeResolve(workDir, opfPath)
                    require(opfFile.exists()) { "EPUB package file missing" }
                    val opf = Jsoup.parse(opfFile, "UTF-8")
                    val opfRoot = opfFile.parentFile ?: workDir
                    val manifest = buildMap<String, String> {
                        opf.select("manifest item").forEach { item ->
                            val id = item.attr("id")
                            val href = item.attr("href")
                            if (id.isNotBlank() && href.isNotBlank()) put(id, href)
                        }
                    }
                    val spineFiles = opf.select("spine itemref").mapNotNull { ref ->
                        manifest[ref.attr("idref")]?.let { href -> runCatching { safeResolve(opfRoot, href) }.getOrNull() }
                    }.filter { it.isFile }
                    require(spineFiles.isNotEmpty()) { "EPUB has no readable chapters" }
                    parsedChapters = spineFiles.map { it.absolutePath }
                    parsedTitles = spineFiles.mapIndexed { index, file -> chapterTitle(file, index) }
                }

                bookDir?.deleteRecursively()
                bookDir = workDir
                chapters = parsedChapters
                chapterTitles = parsedTitles
                chapterIndex = 0
                runOnUiThread {
                    titleView.text = uri.lastPathSegment?.substringAfterLast('/') ?: "EPUB"
                    showChapter(0)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    titleView.text = "EPUB error"
                    Toast.makeText(this, "Could not open EPUB: ${e.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showChapter(index: Int) {
        if (index !in chapters.indices) return
        chapterIndex = index
        val file = File(chapters[index])
        if (!file.isFile) return
        titleView.text = chapterTitles.getOrNull(index) ?: "Chapter ${index + 1}"
        webView.loadDataWithBaseURL(file.parentFile?.toURI()?.toString(), buildChapterHtml(file, index), "text/html", "UTF-8", null)
    }

    private fun buildChapterHtml(file: File, index: Int): String {
        val doc = Jsoup.parse(file, "UTF-8")
        doc.select("img, link, source, video, audio, object").forEach { element ->
            val attr = when (element.tagName()) { "link" -> "href"; "object" -> "data"; else -> "src" }
            rewriteLocalResource(element, attr, file.parentFile ?: file)
        }
        doc.select("a[href]").forEach { anchor ->
            val href = anchor.attr("href")
            if (href.isBlank() || href.startsWith("http://") || href.startsWith("https://") || href.startsWith("mailto:") || href.startsWith("data:")) return@forEach
            val target = runCatching { safeResolve(file.parentFile ?: file, href.substringBefore('#')) }.getOrNull()
            val targetIndex = target?.let { targetFile -> chapters.indexOfFirst { sameFile(it, targetFile) } } ?: -1
            if (targetIndex >= 0) anchor.attr("href", "chapter://$targetIndex") else if (href.startsWith("#")) anchor.attr("href", href)
        }
        val body = doc.body()?.html().orEmpty()
        val heading = chapterTitles.getOrNull(index)?.let { escapeHtml(it) } ?: "Chapter ${index + 1}"
        return """
            <html><head><meta name='viewport' content='width=device-width,initial-scale=1'>
            <style>
              body{font-family:sans-serif;line-height:1.75;padding:24px;max-width:900px;margin:auto;overflow-wrap:anywhere}
              img,svg,video,object{max-width:100%;height:auto}
              h1,h2,h3,h4{line-height:1.3}
              pre{white-space:pre-wrap;overflow-wrap:anywhere}
              a{color:#1565c0}
            </style></head>
            <body><h2>${index + 1}. $heading</h2>$body</body></html>
        """.trimIndent()
    }

    private fun rewriteLocalResource(element: org.jsoup.nodes.Element, attr: String, baseDir: File) {
        val value = element.attr(attr)
        if (value.isBlank() || value.startsWith("http://") || value.startsWith("https://") || value.startsWith("data:") || value.startsWith("file:")) return
        val target = runCatching { safeResolve(baseDir, value.substringBefore('#').substringBefore('?')) }.getOrNull()
        if (target?.isFile == true) element.attr(attr, target.toURI().toString())
    }

    private fun handleChapterLink(uri: Uri): Boolean {
        if (uri.scheme != "chapter") return false
        val index = uri.host?.toIntOrNull() ?: return true
        showChapter(index)
        return true
    }

    private fun find(query: String) {
        val normalized = query.trim()
        if (normalized.isBlank()) return
        Thread {
            val found = chapters.indexOfFirst { path -> runCatching { Jsoup.parse(File(path), "UTF-8").text().contains(normalized, ignoreCase = true) }.getOrDefault(false) }
            runOnUiThread {
                if (found >= 0) {
                    showChapter(found)
                    webView.postDelayed({ webView.findAllAsync(normalized) }, 250)
                } else Toast.makeText(this, "No match found", Toast.LENGTH_SHORT).show()
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

    private fun chapterTitle(file: File, index: Int): String = runCatching {
        val doc = Jsoup.parse(file, "UTF-8")
        doc.title().trim().ifBlank { doc.selectFirst("h1, h2, h3")?.text()?.trim().orEmpty() }.ifBlank { "Chapter ${index + 1}" }
    }.getOrDefault("Chapter ${index + 1}")

    private fun safeResolve(base: File, href: String): File {
        val decoded = Uri.decode(href)
        val target = File(base, decoded).canonicalFile
        val root = bookDir?.canonicalFile ?: base.canonicalFile
        require(target.path == root.path || target.path.startsWith(root.path + File.separator)) { "Unsafe EPUB resource path" }
        return target
    }

    private fun sameFile(path: String, target: File): Boolean = runCatching { File(path).canonicalFile == target.canonicalFile }.getOrDefault(false)

    private fun escapeHtml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        bookDir?.deleteRecursively()
        super.onDestroy()
    }

    companion object { private const val REQUEST_OPEN = 100 }
}
