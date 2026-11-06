package io.github.certhfds2j7.devinlocal

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object AppUpdates {
    const val REPOSITORY = "certhfds2j7/devin-local-android"
    suspend fun check(context: Context): JSONObject? = withContext(Dispatchers.IO) {
        BridgeClient.http.newCall(Request.Builder()
            .url("https://api.github.com/repos/$REPOSITORY/releases/latest")
            .header("Accept", "application/vnd.github+json").build()).execute().use { release ->
            if (release.code == 404) return@withContext null
            if (!release.isSuccessful) throw Exception("暂时无法检查应用更新，请稍后重试")
            val metadata = JSONObject(release.body!!.string())
            val asset = metadata.getJSONArray("assets").objects().firstOrNull { it.optString("name") == "update.json" }
                ?: return@withContext null
            val url = asset.getString("browser_download_url")
            require(url.startsWith("https://github.com/$REPOSITORY/releases/download/"))
            BridgeClient.http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw Exception("更新信息暂时无法下载")
                val manifest = JSONObject(response.body!!.string())
                if (manifest.getInt("versionCode") <= BuildConfig.VERSION_CODE) null
                else {
                    require(manifest.getString("apkUrl").startsWith("https://github.com/$REPOSITORY/releases/download/"))
                    if (manifest.optInt("minSdk", 26) > Build.VERSION.SDK_INT)
                        throw Exception("此新版需要更高版本的 Android")
                    if (manifest.optInt("bridgeApiVersion", 1) != 1)
                        throw Exception("新版协议不兼容，请查看发行说明后手动迁移")
                    manifest
                }
            }
        }
    }
    fun schedule(context: Context) {
        val job = android.app.job.JobInfo.Builder(2701, ComponentName(context, UpdateCheckService::class.java))
            .setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(TimeUnit.DAYS.toMillis(1)).setPersisted(true).build()
        context.getSystemService(android.app.job.JobScheduler::class.java).schedule(job)
    }
    suspend fun download(context: Context, manifest: JSONObject, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val temporary = File(directory, "update.part")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            BridgeClient.http.newCall(Request.Builder().url(manifest.getString("apkUrl")).build()).execute().use { response ->
                if (!response.isSuccessful || !response.request.url.isHttps) throw Exception("应用下载未完成")
                val body = response.body ?: throw Exception("下载内容为空")
                val total = body.contentLength()
                var count = 0L
                body.byteStream().use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(32768)
                        while (true) {
                            val length = input.read(buffer)
                            if (length < 0) break
                            count += length
                            if (count > 200 * 1024 * 1024) throw Exception("安装包大小异常")
                            digest.update(buffer, 0, length); output.write(buffer, 0, length)
                            if (total > 0) progress((count * 100 / total).toInt())
                        }
                    }
                }
            }
            val checksum = digest.digest().joinToString("") { "%02x".format(it) }
            if (!checksum.equals(manifest.getString("sha256"), true)) throw Exception("安装包校验失败，请重新下载")
            verify(context, temporary, manifest.getLong("versionCode"))
            val destination = File(directory, "devin-local-update.apk")
            temporary.copyTo(destination, true); temporary.delete()
            destination
        } catch (error: Exception) { temporary.delete(); throw error }
    }
    @Suppress("DEPRECATION")
    fun verify(context: Context, file: File, version: Long? = null) {
        val manager = context.packageManager
        val flag = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = manager.getPackageArchiveInfo(file.absolutePath, flag) ?: throw Exception("此文件不是有效的 APK")
        val installed = manager.getPackageInfo(context.packageName, flag)
        if (archive.packageName != context.packageName) throw Exception("安装包不属于本应用")
        val nextVersion = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        if (nextVersion <= BuildConfig.VERSION_CODE || (version != null && nextVersion != version))
            throw Exception("安装包版本不匹配，或无需更新")
        val expected = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo!!.apkContentsSigners else installed.signatures!!
        val actual = if (Build.VERSION.SDK_INT >= 28) archive.signingInfo!!.apkContentsSigners else archive.signatures!!
        if (expected.size != actual.size || expected.map { it.toCharsString() }.toSet() != actual.map { it.toCharsString() }.toSet())
            throw Exception("签名与已安装应用不一致，已拒绝更新")
    }
    fun install(context: Context, file: File) {
        verify(context, file)
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName)))
            throw Exception("请允许本应用安装更新，然后再次点击安装")
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}

class UpdateCheckService: android.app.job.JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onStartJob(params: android.app.job.JobParameters): Boolean {
        scope.launch {
            try {
                val update = AppUpdates.check(this@UpdateCheckService)
                if (update != null) {
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.createNotificationChannel(NotificationChannel("app-updates", "应用更新", NotificationManager.IMPORTANCE_DEFAULT))
                    val open = PendingIntent.getActivity(this@UpdateCheckService, 3, Intent(this@UpdateCheckService, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    manager.notify(2701, NotificationCompat.Builder(this@UpdateCheckService, "app-updates")
                        .setSmallIcon(R.drawable.ic_launcher).setContentTitle("Devin 本地助手有新版本")
                        .setContentText("点击打开设置，查看并安装更新").setContentIntent(open).setAutoCancel(true).build())
                }
            } catch (_: Exception) { }
            jobFinished(params, false)
        }
        return true
    }
    override fun onStopJob(params: android.app.job.JobParameters): Boolean {
        scope.coroutineContext.cancelChildren()
        return true
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
