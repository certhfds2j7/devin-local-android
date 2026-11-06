package io.github.certhfds2j7.devinlocal

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.*
import androidx.compose.runtime.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

@SuppressLint("SetJavaScriptEnabled")
private fun offlineWebView(view: WebView, ready: () -> Unit) {
    val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/",
        WebViewAssetLoader.AssetsPathHandler(view.context)).build()
    view.settings.javaScriptEnabled = true
    view.settings.allowFileAccess = false
    view.settings.allowContentAccess = false
    view.settings.domStorageEnabled = false
    view.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    view.webViewClient = object: WebViewClient() {
        override fun shouldInterceptRequest(v: WebView?, request: WebResourceRequest): WebResourceResponse? {
            if (request.url.host != "appassets.androidplatform.net")
                return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), "".byteInputStream())
            return loader.shouldInterceptRequest(request.url)
        }
        override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest) = true
    }
}

@Composable
fun CodeEditor(model: AppModel, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    var web by remember { mutableStateOf<WebView?>(null) }
    val main = remember { Handler(Looper.getMainLooper()) }
    var loaded by remember { mutableStateOf(false) }
    var rendered by remember { mutableIntStateOf(-1) }
    fun render() {
        val view = web ?: return
        if (!loaded || rendered == model.editorLoadId) return
        view.evaluateJavascript("window.loadFile(" + JSONObject.quote(model.editorText) + "," +
            JSONObject.quote(model.filePath) + "," + dark + ");", null)
        rendered = model.editorLoadId
    }
    AndroidView(modifier = modifier, factory = { context ->
        WebView(context).also { view ->
            web = view
            offlineWebView(view) { }
            view.addJavascriptInterface(object {
                @JavascriptInterface fun ready() { main.post { loaded = true; render() } }
                @JavascriptInterface fun changed(text: String) { main.post { model.edit(text) } }
            }, "Native")
            view.loadUrl("https://appassets.androidplatform.net/assets/web/editor.html")
        }
    }, update = { render() })
    DisposableEffect(Unit) {
        onDispose { web?.removeJavascriptInterface("Native"); web?.destroy() }
    }
}

@Composable
fun TerminalPanel(model: AppModel, modifier: Modifier = Modifier) {
    var web by remember { mutableStateOf<WebView?>(null) }
    val main = remember { Handler(Looper.getMainLooper()) }
    val id = model.terminalId
    AndroidView(modifier = modifier, factory = { context ->
        WebView(context).also { view ->
            web = view
            offlineWebView(view) { }
            view.setBackgroundColor(android.graphics.Color.rgb(17, 24, 39))
            view.addJavascriptInterface(object {
                @JavascriptInterface fun ready() {
                    main.post {
                        if (id == null) return@post
                        model.terminalSocket?.close(1000, "reconnect")
                        model.terminalSocket = model.api.socket("/v1/terminals/$id/stream", object: WebSocketListener() {
                            override fun onOpen(socket: WebSocket, response: Response) {
                                main.post { view.evaluateJavascript("window.fitTerminal();", null) }
                            }
                            override fun onMessage(socket: WebSocket, text: String) {
                                try {
                                    val data = JSONObject(text)
                                    if (data.optString("type") == "output") main.post {
                                        view.evaluateJavascript("window.writeTerminal(" + JSONObject.quote(data.getString("data")) + ");", null)
                                    }
                                } catch (_: Exception) { }
                            }
                            override fun onFailure(socket: WebSocket, t: Throwable, response: Response?) {
                                main.post { model.note = "终端连接中断，可关闭后重新打开" }
                            }
                            override fun onClosed(socket: WebSocket, code: Int, reason: String) {
                                main.post {
                                    if (model.terminalSocket === socket) {
                                        model.terminalSocket = null
                                        model.terminalId = null
                                        model.note = "本地终端已结束"
                                    }
                                }
                            }
                        })
                    }
                }
                @JavascriptInterface fun input(data: String) {
                    model.terminalSocket?.send(JSONObject().put("type", "input").put("data", data).toString())
                }
                @JavascriptInterface fun resize(cols: Int, rows: Int) {
                    model.terminalSocket?.send(JSONObject().put("type", "resize").put("cols", cols).put("rows", rows).toString())
                }
            }, "Native")
            view.loadUrl("https://appassets.androidplatform.net/assets/web/terminal.html")
        }
    })
    DisposableEffect(id) {
        onDispose {
            model.terminalSocket?.close(1000, "ui detached")
            model.terminalSocket = null
            web?.removeJavascriptInterface("Native"); web?.destroy()
        }
    }
}
