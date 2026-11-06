package io.github.certhfds2j7.devinlocal

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object LinkState {
    val changes = MutableStateFlow("")
    private const val ALIAS = "devin-local-pairing-v1"
    private fun prefs(c: Context) = c.getSharedPreferences("connection", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build())
                generateKey()
            }
        }
        return store.getKey(ALIAS, null) as SecretKey
    }
    fun save(c: Context, token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        prefs(c).edit().putString("pair", Base64.encodeToString(cipher.iv + cipher.doFinal(token.toByteArray()), Base64.NO_WRAP)).apply()
    }
    fun token(c: Context): String? = try {
        val value = prefs(c).getString("pair", null)
        if (value == null) null else {
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)))
        }
    } catch (_: Exception) { null }
    fun expected(c: Context): String? = prefs(c).getString("pending", null)
    fun expect(c: Context, value: String) { prefs(c).edit().putString("pending", value).apply() }
    fun clear(c: Context) { prefs(c).edit().remove("pair").apply() }
}

class ApiException(val status: Int, message: String): Exception(message)

class BridgeClient(private val context: Context) {
    companion object {
        const val BASE = "http://127.0.0.1:8765"
        val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS).build()
    }
    suspend fun bytes(path: String, method: String = "GET", body: ByteArray? = null, type: String = "application/json"): ByteArray =
        withContext(Dispatchers.IO) {
            val token = LinkState.token(context) ?: throw ApiException(401, "请先连接手机本地环境")
            val request = Request.Builder().url(BASE + path).header("Authorization", "Bearer $token")
                .method(method, if (method == "GET") null else (body ?: ByteArray(0)).toRequestBody(type.toMediaType())).build()
            http.newCall(request).execute().use { response ->
                val data = response.body?.bytes() ?: ByteArray(0)
                if (!response.isSuccessful) {
                    val message = try { JSONObject(String(data)).optString("error", "本地操作未完成") }
                                  catch (_: Exception) { "本地操作未完成（${response.code}）" }
                    throw ApiException(response.code, message)
                }
                data
            }
        }
    suspend fun obj(path: String, method: String = "GET", data: JSONObject? = null) =
        JSONObject(String(bytes(path, method, data?.toString()?.toByteArray())))
    suspend fun array(path: String) = JSONArray(String(bytes(path)))
    fun socket(path: String, listener: WebSocketListener): WebSocket {
        val token = LinkState.token(context) ?: throw ApiException(401, "请先连接本地环境")
        return http.newWebSocket(Request.Builder().url(BASE.replace("http:", "ws:") + path)
            .header("Authorization", "Bearer $token").build(), listener)
    }
}

object TermuxLauncher {
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    fun login(context: Context) {
        val command = "proot-distro login devin-android-gate -- /bin/bash -lc 'export PATH=\"\$HOME/.local/bin:\$PATH\"; exec devin auth login --force-manual-token-flow'"
        context.startService(Intent("com.termux.RUN_COMMAND").setClassName("com.termux", "com.termux.app.RunCommandService")
            .putExtra("com.termux.RUN_COMMAND_PATH", "\$PREFIX/bin/bash")
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", false))
    }
    fun launch(context: Context) {
        if (ContextCompat.checkSelfPermission(context, PERMISSION) != PackageManager.PERMISSION_GRANTED)
            throw IllegalStateException("请先授予连接 Termux 的权限")
        val action = context.packageName + ".PAIR." + UUID.randomUUID()
        LinkState.expect(context, action)
        val callback = PendingIntent.getBroadcast(context, 1741,
            Intent(context, TermuxResultReceiver::class.java).setAction(action),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE)
        val script = context.assets.open("bootstrap.sh").bufferedReader().use { it.readText() }
        val intent = Intent("com.termux.RUN_COMMAND").setClassName("com.termux", "com.termux.app.RunCommandService")
            .putExtra("com.termux.RUN_COMMAND_PATH", "\$PREFIX/bin/bash")
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-s"))
            .putExtra("com.termux.RUN_COMMAND_STDIN", script)
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND_CUSTOM_LOG_LEVEL", 0)
            .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", callback)
        context.startService(intent)
        LinkState.changes.value = "正在连接本地环境，请稍候……"
    }
}

class TermuxResultReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LinkState.expected(context)) return
        val result = intent.getBundleExtra("result")
        if (result == null || result.getInt("exitCode", -1) != 0 || result.getInt("err", 0) != 0) {
            LinkState.changes.value = "本地环境未能启动。请确认 Termux 已初始化并允许应用连接。"
            return
        }
        try {
            val line = result.getString("stdout").orEmpty().lineSequence().last { it.startsWith("PAIRING:") }
            val data = JSONObject(line.removePrefix("PAIRING:"))
            val token = data.getString("token")
            require(token.length >= 40 && data.getInt("port") == 8765)
            LinkState.save(context, token)
            LinkState.changes.value = "本地环境已连接"
        } catch (_: Exception) {
            LinkState.changes.value = "配对未完成，请再次连接本地环境"
        }
    }
}

class ConnectionService: Service() {
    private var socket: WebSocket? = null
    private val handler = Handler(Looper.getMainLooper())
    private var stopping = false
    private val manager get() = getSystemService(NotificationManager::class.java)
    override fun onBind(intent: Intent?) = null
    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "local-tasks").setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Devin 本地助手").setContentText(message).setContentIntent(open)
            .setOngoing(true).setSilent(true).build()
    }
    override fun onCreate() {
        super.onCreate()
        manager.createNotificationChannel(NotificationChannel("local-tasks", "手机本地任务", NotificationManager.IMPORTANCE_LOW))
        startForeground(2001, notification("本地环境已连接"))
        connect()
    }
    private fun connect() {
        if (stopping || LinkState.token(this) == null) return
        try {
            socket = BridgeClient(this).socket("/v1/events", object: WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val event = JSONObject(text)
                        val message = when {
                            event.optBoolean("anyWaiting") -> "任务正在等待你的工具授权"
                            event.optBoolean("anyBusy") -> "代理正在手机本地处理任务"
                            event.optString("state") == "interrupted" -> "任务连接中断，打开应用恢复会话"
                            else -> "本地环境已连接"
                        }
                        manager.notify(2001, notification(message))
                    } catch (_: Exception) { }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    manager.notify(2001, notification("本地连接中断，正在尝试重新连接"))
                    handler.postDelayed({ connect() }, 8000)
                }
            })
        } catch (_: Exception) { }
    }
    override fun onDestroy() {
        stopping = true
        handler.removeCallbacksAndMessages(null)
        socket?.close(1000, "closed")
        super.onDestroy()
    }
}
