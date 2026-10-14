package com.videotoolbox.saver

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.TimeUnit

data class ParseResult(
    val title: String,
    val author: String,
    val cover: String,
    val videoUrl: String,
    val images: List<String> = emptyList()
) {
    val isGallery: Boolean get() = images.isNotEmpty()
}

class ParseException(message: String) : Exception(message)

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    const val MOBILE_UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

    fun get(url: String, ua: String = MOBILE_UA): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ParseException("页面请求失败 HTTP ${resp.code}")
            return resp.body?.string() ?: throw ParseException("页面内容为空")
        }
    }
}

/** 从分享口令文本中提取链接 */
fun extractUrl(text: String): String? =
    Regex("https?://[^\\s，,。\"'“”‘’]+", RegexOption.IGNORE_CASE).find(text)?.value

interface Parser {
    fun match(url: String): Boolean
    suspend fun parse(url: String): ParseResult
}

/** JSON 字符串反转义（处理 \u002F \/ 等） */
fun unescapeJsonString(raw: String): String = try {
    JSONTokener("\"" + raw + "\"").nextValue() as String
} catch (e: Exception) {
    raw.replace("\\/", "/").replace("\\u002F", "/")
}

// ---------------------------------------------------------------- 抖音

object DouyinParser : Parser {
    override fun match(url: String) =
        url.contains("douyin.com") || url.contains("iesdouyin.com")

    override suspend fun parse(url: String): ParseResult = withContext(Dispatchers.IO) {
        val html = Http.get(url)
        val m = Regex("window\\._ROUTER_DATA\\s*=\\s*(\\{[\\s\\S]*?\\})\\s*</script>").find(html)
            ?: throw ParseException("未能从抖音页面取到视频数据，链接可能已失效或需要更新")
        val json = JSONObject(m.groupValues[1])

        val videoUrl = findPlayAddr(json)
            ?: throw ParseException("未找到无水印视频地址（该作品可能是图集或已删除）")
        val images = findImages(json)
        ParseResult(
            title = findFirstString(json, "desc") ?: "",
            author = findFirstString(json, "nickname") ?: "",
            cover = findCover(json) ?: "",
            videoUrl = videoUrl,
            images = images
        )
    }

    /** 递归查找第一个 play_addr.url_list */
    private fun findPlayAddr(node: Any?): String? {
        return when (node) {
            is JSONObject -> {
                val playAddr = node.optJSONObject("play_addr")
                if (playAddr != null) {
                    val list = playAddr.optJSONArray("url_list")
                    if (list != null && list.length() > 0) return list.getString(0)
                }
                for (key in node.keys()) { findPlayAddr(node.opt(key))?.let { return it } }
                null
            }
            is JSONArray -> {
                for (i in 0 until node.length()) { findPlayAddr(node.opt(i))?.let { return it } }
                null
            }
            else -> null
        }
    }

    /** 递归查找图集 images[].url_list */
    private fun findImages(node: Any?): List<String> {
        val out = mutableListOf<String>()
        fun walk(n: Any?) {
            when (n) {
                is JSONObject -> {
                    val images = n.optJSONArray("images")
                    if (images != null && images.length() > 0) {
                        for (i in 0 until images.length()) {
                            val item = images.optJSONObject(i) ?: continue
                            val urls = item.optJSONArray("url_list") ?: continue
                            if (urls.length() > 0) out.add(urls.getString(0))
                        }
                    }
                    for (key in n.keys()) walk(n.opt(key))
                }
                is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
            }
        }
        walk(node)
        return out.distinct()
    }

    private fun findCover(node: Any?): String? {
        fun walk(n: Any?): String? {
            return when (n) {
                is JSONObject -> {
                    val cover = n.optJSONObject("cover")
                    if (cover != null) {
                        val urls = cover.optJSONArray("url_list")
                        if (urls != null && urls.length() > 0) return urls.getString(0)
                    }
                    for (key in n.keys()) walk(n.opt(key))?.let { return it }
                    null
                }
                is JSONArray -> {
                    for (i in 0 until n.length()) walk(n.opt(i))?.let { return it }
                    null
                }
                else -> null
            }
        }
        return walk(node)
    }

    private fun findFirstString(node: Any?, key: String): String? {
        fun walk(n: Any?): String? {
            return when (n) {
                is JSONObject -> {
                    val v = n.opt(key)
                    if (v is String && v.isNotBlank()) return v
                    for (k in n.keys()) walk(n.opt(k))?.let { return it }
                    null
                }
                is JSONArray -> {
                    for (i in 0 until n.length()) walk(n.opt(i))?.let { return it }
                    null
                }
                else -> null
            }
        }
        return walk(node)
    }
}

// ---------------------------------------------------------------- 快手

object KuaishouParser : Parser {
    override fun match(url: String) =
        url.contains("kuaishou.com") || url.contains("chenzhongtech.com")

    override suspend fun parse(url: String): ParseResult = withContext(Dispatchers.IO) {
        val html = Http.get(url)
        val videoUrl = Regex("\"photoUrl\"\\s*:\\s*\"([^\"]+)\"").find(html)?.let { unescapeJsonString(it.groupValues[1]) }
            ?: Regex("\"url\"\\s*:\\s*\"(https?:[^\"]*(?:mp4|photo)[^\"]*)\"").find(html)?.let { unescapeJsonString(it.groupValues[1]) }
            ?: throw ParseException("未能从快手页面取到视频地址，链接可能已失效")
        val title = Regex("\"caption\"\\s*:\\s*\"([^\"]*)\"").find(html)?.let { unescapeJsonString(it.groupValues[1]) } ?: ""
        ParseResult(title = title, author = "", cover = "", videoUrl = videoUrl)
    }
}

// ---------------------------------------------------------------- 通用兜底（og 标签 / 常见字段）

object GenericParser : Parser {
    override fun match(url: String) = true

    override suspend fun parse(url: String): ParseResult = withContext(Dispatchers.IO) {
        val html = Http.get(url)

        val videoUrl = meta(html, "og:video:url")
            ?: meta(html, "og:video:secure_url")
            ?: meta(html, "og:video")
            ?: Regex("\"(?:videoUrl|playUrl|video_url|play_addr)\"\\s*:\\s*\"(https?:[^\"]+)\"").find(html)
                ?.let { unescapeJsonString(it.groupValues[1]) }
            ?: throw ParseException("暂不支持该链接，可复制链接内容反馈更新解析规则")

        ParseResult(
            title = meta(html, "og:title") ?: "",
            author = "",
            cover = meta(html, "og:image") ?: "",
            videoUrl = unescapeJsonString(videoUrl)
        )
    }

    private fun meta(html: String, prop: String): String? =
        Regex("<meta[^>]+(?:property|name)=[\"']$prop[\"'][^>]+content=[\"']([^\"']+)[\"']").find(html)
            ?.groupValues?.get(1)
            ?: Regex("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+(?:property|name)=[\"']$prop[\"']").find(html)
                ?.groupValues?.get(1)
}

val PARSERS = listOf(BugpkApiParser, DouyinParser, KuaishouParser, GenericParser)

/**
 * 公开解析接口优先（服务器端持有有效 Cookie/签名，成功率最高）。
 * bugpk.com 由开源项目 LiYiCha/short_videos 作者维护。
 */
object BugpkApiParser : Parser {
    private const val BASE = "https://api.bugpk.com/api/"

    private fun endpointFor(url: String): String? = when {
        url.contains("douyin.com") || url.contains("iesdouyin.com") -> "douyin"
        url.contains("kuaishou.com") || url.contains("chenzhongtech.com") -> "kuaishou"
        else -> null
    }

    override fun match(url: String) = endpointFor(url) != null

    override suspend fun parse(url: String): ParseResult = withContext(Dispatchers.IO) {
        val api = BASE + endpointFor(url)
        val body = Http.get("$api?url=${java.net.URLEncoder.encode(url, "UTF-8")}")
        val root = JSONObject(body)
        if (root.optInt("code") != 200) {
            throw ParseException(root.optString("msg", "解析接口返回失败"))
        }
        val data = root.optJSONObject("data") ?: throw ParseException("解析接口无数据")

        val images = mutableListOf<String>()
        val imgs = data.optJSONArray("images")
        if (imgs != null) {
            for (i in 0 until imgs.length()) {
                when (val item = imgs.opt(i)) {
                    is String -> images.add(item)
                    is JSONObject -> {
                        val u = item.optString("url", "")
                        when {
                            u.isNotBlank() -> images.add(u)
                            else -> {
                                val list = item.optJSONArray("url_list")
                                if (list != null && list.length() > 0) images.add(list.getString(0))
                            }
                        }
                    }
                }
            }
        }

        var videoUrl = data.optString("url", "")
        if (videoUrl.isBlank()) {
            val backups = data.optJSONArray("video_backup")
            if (backups != null && backups.length() > 0) {
                val first = backups.optJSONObject(0)
                videoUrl = first?.optString("url", "") ?: ""
            }
        }
        if (videoUrl.isBlank() && images.isEmpty()) {
            throw ParseException("接口未返回可保存的内容")
        }

        ParseResult(
            title = data.optString("title", ""),
            author = data.optJSONObject("author")?.optString("name", "") ?: "",
            cover = data.optString("cover", ""),
            videoUrl = videoUrl,
            images = images.distinct()
        )
    }
}

suspend fun parseShareText(text: String): ParseResult {
    val url = extractUrl(text) ?: throw ParseException("没有识别到链接，请粘贴完整的分享文本")
    var lastError: Exception? = null
    for (parser in PARSERS.filter { it.match(url) }) {
        try {
            return parser.parse(url)
        } catch (e: Exception) {
            lastError = e
        }
    }
    throw lastError ?: ParseException("解析失败")
}
