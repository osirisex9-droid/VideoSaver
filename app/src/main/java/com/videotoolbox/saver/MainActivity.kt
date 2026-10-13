package com.videotoolbox.saver

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.squareup.picasso.Picasso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var input: EditText
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var resultCard: LinearLayout
    private lateinit var coverView: ImageView
    private lateinit var titleView: TextView
    private lateinit var authorView: TextView
    private lateinit var btnParse: TextView
    private lateinit var btnSave: TextView

    private var result: ParseResult? = null
    private var parsing = false
    private var saving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        input = findViewById(R.id.input)
        progress = findViewById(R.id.progress)
        progressText = findViewById(R.id.progressText)
        resultCard = findViewById(R.id.resultCard)
        coverView = findViewById(R.id.cover)
        titleView = findViewById(R.id.title)
        authorView = findViewById(R.id.author)
        btnParse = findViewById(R.id.btnParse)
        btnSave = findViewById(R.id.btnSave)

        findViewById<TextView>(R.id.btnPaste).setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
            if (text.isNotBlank()) {
                input.setText(text)
                input.setSelection(text.length)
            } else {
                toast("剪贴板是空的")
            }
        }

        btnParse.setOnClickListener { onParse() }
        btnSave.setOnClickListener { onSave() }
        findViewById<TextView>(R.id.btnCopy).setOnClickListener {
            val r = result ?: return@setOnClickListener
            copy(r.videoUrl)
            toast("直链已复制")
        }

        // 从其他 App「分享到」进来：自动填充并解析
        val shared = intent?.getStringExtra(android.content.Intent.EXTRA_TEXT)
        if (!shared.isNullOrBlank()) {
            input.setText(shared)
            onParse()
        }
    }

    private fun onParse() {
        if (parsing || saving) return
        val text = input.text.toString()
        val url = extractUrl(text)
        if (url == null) {
            toast("没有识别到链接，请粘贴完整的分享文本")
            return
        }
        parsing = true
        result = null
        resultCard.visibility = View.GONE
        btnSave.visibility = View.GONE
        btnParse.text = "解析中..."
        setBusy(true, "正在解析...")

        lifecycleScope.launch {
            try {
                val r = parseShareText(text)
                result = r
                showResult(r)
            } catch (e: Exception) {
                toast(e.message ?: "解析失败")
            } finally {
                parsing = false
                btnParse.text = "解析视频"
                setBusy(false, "")
            }
        }
    }

    private fun showResult(r: ParseResult) {
        resultCard.visibility = View.VISIBLE
        titleView.text = r.title.ifBlank { "未获取到标题" }
        authorView.text = listOfNotNull(
            r.author.takeIf { it.isNotBlank() }?.let { "作者：$it" },
            if (r.isGallery) "图集（${r.images.size} 张图片）" else null
        ).joinToString("　")
        if (r.cover.isNotBlank()) {
            coverView.visibility = View.VISIBLE
            Picasso.get().load(r.cover).fit().centerCrop().into(coverView)
        } else {
            coverView.visibility = View.GONE
        }
        btnSave.visibility = View.VISIBLE
        btnSave.text = if (r.isGallery) "保存图集到相册" else "保存视频到相册"
    }

    private fun onSave() {
        val r = result ?: return
        if (parsing || saving) return
        saving = true
        btnSave.isEnabled = false
        setBusy(true, "准备下载...")

        lifecycleScope.launch {
            try {
                val savedName = withContext(Dispatchers.IO) {
                    if (r.isGallery) saveGallery(r) else saveSingle(r)
                }
                toast(savedName)
            } catch (e: Exception) {
                toast(e.message ?: "保存失败")
            } finally {
                saving = false
                btnSave.isEnabled = true
                setBusy(false, "")
            }
        }
    }

    private fun saveSingle(r: ParseResult): String {
        updateProgress("下载视频...")
        val file = MediaSaver.downloadToCache(cacheDir, r.videoUrl, "video_${System.currentTimeMillis()}.mp4") { p ->
            updateProgress("下载中 $p%")
        }
        updateProgress("写入相册...")
        val name = safeTitle(r.title).ifBlank { "video_${System.currentTimeMillis()}" }
        val saved = MediaSaver.saveVideo(this, file, name)
        file.delete()
        return "已保存到相册：$saved"
    }

    private fun saveGallery(r: ParseResult): String {
        val files = mutableListOf<File>()
        r.images.forEachIndexed { i, u ->
            updateProgress("下载图片 ${i + 1}/${r.images.size}")
            files.add(MediaSaver.downloadToCache(cacheDir, u, "img_${System.currentTimeMillis()}_$i.jpg") { })
        }
        updateProgress("写入相册...")
        val count = MediaSaver.saveImages(this, files)
        files.forEach { it.delete() }
        return "已保存 $count 张图片到相册"
    }

    private fun safeTitle(t: String) =
        t.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "").take(60)

    private fun setBusy(busy: Boolean, text: String) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        progressText.visibility = if (busy) View.VISIBLE else View.GONE
        progressText.text = text
    }

    private fun updateProgress(text: String) {
        runOnUiThread { progressText.text = text }
    }

    private fun copy(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("link", text))
    }

    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }
}
