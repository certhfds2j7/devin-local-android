package io.github.certhfds2j7.devinlocal

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

fun configValues(config: JSONObject) = config.optJSONArray("options")?.objects()?.flatMap {
    it.optJSONArray("options")?.objects() ?: listOf(it)
}.orEmpty()

fun configCategory(config: JSONObject): String {
    val category = config.optString("category")
    val id = config.optString("id")
    return when {
        category.isNotEmpty() -> category
        id.contains("thought", true) || id.contains("reason", true) -> "thought_level"
        id.contains("model", true) -> "model"
        id.contains("mode", true) -> "mode"
        else -> "other"
    }
}

fun optionLabel(category: String, value: String, name: String): String = when (category) {
    "mode" -> when (value) {
        "accept-edits", "code" -> "编写代码"; "smart" -> "智能审批"; "ask" -> "只读问答"; "plan" -> "规划任务"; else -> name
    }
    "thought_level" -> when (value.lowercase()) {
        "none", "off", "disabled" -> "关闭"; "minimal" -> "最低"; "low" -> "低"; "medium" -> "中";
        "high" -> "高"; "xhigh", "extra_high" -> "很高"; "max", "maximum" -> "最高"; else -> name
    }
    else -> name
}

fun currentConfigLabel(meta: JSONObject?, category: String): String {
    if (meta == null) return "尚未开始对话"
    val config = meta.optJSONArray("configOptions")?.objects()?.firstOrNull { configCategory(it) == category }
    if (config != null) {
        val value = config.optString("currentValue")
        val option = configValues(config).firstOrNull { it.optString("value") == value }
        return optionLabel(category, value, option?.optString("name") ?: value.ifBlank { "代理未提供" })
    }
    val legacy = meta.optJSONObject(if (category == "model") "models" else "modes")
    val value = legacy?.optString(if (category == "model") "currentModelId" else "currentModeId").orEmpty()
    val option = legacy?.optJSONArray(if (category == "model") "availableModels" else "availableModes")?.objects()?.firstOrNull {
        it.optString(if (category == "model") "modelId" else "id") == value
    }
    if (category == "thought_level") return "当前模型未提供"
    return optionLabel(category, value, option?.optString("name") ?: value.ifBlank { "代理未提供" })
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RichChatPanel(model: AppModel, modifier: Modifier, openLink: (String) -> Unit) {
    val dark = isSystemInDarkTheme()
    val callback by rememberUpdatedState(openLink)
    val pid = model.projectId
    val session = model.agent?.optString("id")
    val main = remember { Handler(Looper.getMainLooper()) }
    var view by remember { mutableStateOf<WebView?>(null) }
    var loaded by remember { mutableStateOf(false) }
    val rows = JSONArray(model.messages.map { line ->
        JSONObject().put("role", line.role).put("text", line.text).put("files", JSONArray(line.files))
    }).toString()
    fun render() {
        if (loaded) view?.evaluateJavascript("window.renderChat($rows,$dark);", null)
    }
    AndroidView(modifier = modifier.clipToBounds(), factory = { context ->
        WebView(context).also { web ->
            view = web
            web.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            offlineWebView(web, resource = { request ->
                if (request.url.path != "/project-image") null else {
                    val path = request.url.getQueryParameter("path").orEmpty()
                    val mime = java.net.URLConnection.guessContentTypeFromName(path) ?: ""
                    try {
                        require(mime in listOf("image/png", "image/jpeg", "image/webp", "image/gif"))
                        val bytes = runBlocking { model.api.bytes("/v1/projects/$pid/download?path=" + encoded(path), limit = 8 * 1024 * 1024) }
                        WebResourceResponse(mime, null, bytes.inputStream())
                    } catch (_: Exception) {
                        WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), "".byteInputStream())
                    }
                }
            }) { }
            web.addJavascriptInterface(object {
                @JavascriptInterface fun ready() { main.post { loaded = true } }
                @JavascriptInterface fun openLink(value: String) {
                    main.post { if (model.projectId == pid && model.agent?.optString("id") == session) callback(value) }
                }
            }, "Native")
            web.loadUrl("https://appassets.androidplatform.net/assets/web/chat.html")
        }
    }, update = { render() }, onRelease = { web ->
        web.removeJavascriptInterface("Native"); web.destroy()
    })
}
