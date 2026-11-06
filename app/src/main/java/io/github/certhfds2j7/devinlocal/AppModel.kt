package io.github.certhfds2j7.devinlocal

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun encoded(value: String): String = URLEncoder.encode(value, "UTF-8")
data class ChatLine(val role: String, val text: String)

class AppModel(app: Application): AndroidViewModel(app) {
    val api = BridgeClient(app)
    private val preferences = app.getSharedPreferences("workspace", 0)
    var tab by mutableIntStateOf(0)
    var connected by mutableStateOf(false)
    var connectionMessage by mutableStateOf("连接手机上的本地环境，开始编程")
    var note by mutableStateOf("")
    var operations by mutableIntStateOf(0)
    var projects by mutableStateOf<List<JSONObject>>(emptyList())
    var selected by mutableStateOf<JSONObject?>(null)
    var files by mutableStateOf<List<JSONObject>>(emptyList())
    var folder by mutableStateOf("")
    var searchResults by mutableStateOf<List<JSONObject>>(emptyList())
    var conversations by mutableStateOf<List<JSONObject>>(emptyList())
    var agent by mutableStateOf<JSONObject?>(null)
    var agentBusy by mutableStateOf(false)
    val messages = mutableStateListOf<ChatLine>()
    var permission by mutableStateOf<JSONObject?>(null)
    var chatConnected by mutableStateOf(false)
    var chatInput by mutableStateOf("")
    var filePath by mutableStateOf("")
    var editorText by mutableStateOf("")
    var originalText by mutableStateOf("")
    var fileRevision by mutableStateOf("missing")
    var editorLoadId by mutableIntStateOf(0)
    var conflict by mutableStateOf(false)
    var gitStatus by mutableStateOf("")
    var gitDiff by mutableStateOf("")
    var terminalId by mutableStateOf<String?>(null)
    var terminalSocket: WebSocket? = null
    var agentSocket: WebSocket? = null
    var updateMessage by mutableStateOf("")
    var appUpdate by mutableStateOf<JSONObject?>(null)
    private var lastSequence = 0L
    private var reconnectJob: Job? = null
    private val draftJobs = mutableMapOf<String, Job>()
    private val draftMutex = Mutex()
    val projectId get() = selected?.optString("id").orEmpty()
    val dirty get() = filePath.isNotEmpty() && editorText != originalText

    init {
        viewModelScope.launch {
            LinkState.changes.collect { value ->
                if (value.isNotEmpty()) {
                    connectionMessage = value
                    if (value == "本地环境已连接") refresh()
                }
            }
        }
        if (LinkState.token(app) != null) refresh()
    }

    fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            operations++
            try { block() }
            catch (error: Exception) {
                if (error !is CancellationException)
                    note = error.message?.take(180) ?: "操作未完成，请重试"
            }
            finally { operations-- }
        }
    }
    private fun startNotifications() {
        try { getApplication<Application>().startForegroundService(Intent(getApplication(), ConnectionService::class.java)) }
        catch (_: Exception) { }
    }
    fun refresh() = act {
        try {
            val health = api.obj("/v1/health")
            if (health.optInt("apiVersion") != 1) {
                connected = false
                connectionMessage = "本地接口版本不兼容，请更新客户端并重新连接"
                return@act
            }
            connected = true
            connectionMessage = "手机本地环境已连接"
            projects = api.array("/v1/projects").objects()
            val remembered = selected?.optString("id") ?: preferences.getString("project", null)
            selected = projects.firstOrNull { it.optString("id") == remembered }
            refreshSessions()
            startNotifications()
            if (selected != null) refreshFiles()
        } catch (_: Exception) {
            connected = false
            connectionMessage = "本地环境未连接，点击“连接环境”恢复"
        }
    }
    fun chooseProject(value: JSONObject) {
        selected = value
        preferences.edit().putString("project", projectId).apply()
        folder = ""; files = emptyList(); filePath = ""; searchResults = emptyList()
        agentSocket?.close(1000, "switch")
        agent = null; messages.clear(); permission = null; agentBusy = false
        act { refreshFiles(); refreshSessions() }
    }
    fun createProject(name: String, kind: String) = act {
        val created = api.obj("/v1/projects", "POST", JSONObject().put("name", name).put("kind", kind))
        projects = api.array("/v1/projects").objects()
        chooseProject(created)
        note = "项目已创建在手机本地"
    }
    suspend fun refreshFiles(path: String = folder) {
        if (projectId.isEmpty()) return
        folder = path
        files = api.array("/v1/projects/$projectId/files?path=" + encoded(path)).objects()
    }
    fun enterFolder(path: String) = act { refreshFiles(path) }
    fun search(query: String) = act {
        searchResults = api.array("/v1/projects/$projectId/search?q=" + encoded(query)).objects()
    }
    private fun draftFile(path: String = filePath): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(("$projectId:$path").toByteArray()).joinToString("") { "%02x".format(it) }
        return File(getApplication<Application>().filesDir, "drafts/$hash.json")
    }
    fun openFile(path: String, restoreDraft: Boolean = true) = act {
        val data = api.obj("/v1/projects/$projectId/file?path=" + encoded(path))
        filePath = path; originalText = data.getString("content")
        fileRevision = data.getString("revision"); editorText = originalText
        if (restoreDraft && draftFile(path).exists()) {
            try {
                val draft = JSONObject(draftFile(path).readText())
                editorText = draft.getString("text")
                fileRevision = draft.getString("revision")
                note = "已恢复未保存草稿；保存时会再次检查文件版本"
            } catch (_: Exception) { }
        }
        conflict = false; editorLoadId++; tab = 2
    }
    fun edit(text: String) {
        editorText = text
        if (filePath.isEmpty()) return
        val file = draftFile()
        file.parentFile?.mkdirs()
        val data = JSONObject().put("text", text).put("revision", fileRevision).toString()
        draftJobs[file.path]?.cancel()
        draftJobs[file.path] = viewModelScope.launch {
            delay(150)
            draftMutex.withLock {
                withContext(Dispatchers.IO) { file.writeText(data) }
            }
        }
    }
    fun saveFile() = act {
        val savedProject = projectId
        val savedPath = filePath
        val savedText = editorText
        val draft = draftFile()
        try {
            val result = api.obj("/v1/projects/$savedProject/file?path=" + encoded(savedPath), "PUT",
                JSONObject().put("content", savedText).put("revision", fileRevision))
            draftJobs.remove(draft.path)?.cancel()
            draftMutex.withLock { withContext(Dispatchers.IO) { draft.delete() } }
            if (projectId == savedProject && filePath == savedPath) {
                fileRevision = result.getString("revision"); originalText = savedText
                if (editorText != savedText) edit(editorText)
            }
            note = "文件已保存"
        } catch (error: ApiException) {
            if (error.status == 409) conflict = true
            throw error
        }
    }
    fun createFile(path: String) = act {
        api.obj("/v1/projects/$projectId/file?path=" + encoded(path), "PUT",
            JSONObject().put("content", "").put("revision", "missing"))
        refreshFiles()
        openFile(path)
    }
    fun discardDraftAndReload() {
        val path = filePath
        val draft = draftFile()
        draftJobs.remove(draft.path)?.cancel()
        act {
            draftMutex.withLock { withContext(Dispatchers.IO) { draft.delete() } }
            openFile(path, false)
        }
    }
    suspend fun refreshSessions() {
        conversations = api.array("/v1/agents").objects().filter { it.optString("projectId") == projectId }
    }
    fun startChat(resume: JSONObject? = null) = act {
        if (projectId.isEmpty()) throw IllegalStateException("请先选择项目")
        val request = JSONObject().put("projectId", projectId)
        if (resume != null) request.put("resumeId", resume.getString("id"))
        val metadata = api.obj("/v1/agents", "POST", request)
        agent = metadata; agentBusy = metadata.optString("state") in listOf("running", "waiting")
        messages.clear(); permission = null; lastSequence = 0
        val history = api.array("/v1/agents/" + metadata.getString("id") + "/history")
        history.objects().forEach { consume(it) }
        connectChat()
        refreshSessions()
        tab = 1
    }
    fun connectChat() {
        val id = agent?.optString("id") ?: return
        reconnectJob?.cancel()
        agentSocket?.close(1000, "reconnect")
        agentSocket = api.socket("/v1/agents/$id/stream?after=$lastSequence", object: WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                viewModelScope.launch { chatConnected = true }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                viewModelScope.launch { try { consume(JSONObject(text)) } catch (_: Exception) { } }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                viewModelScope.launch {
                    chatConnected = false
                    note = "对话连接中断，任务可能仍在本地运行；可点击重新连接"
                }
            }
        })
    }
    private fun addAssistant(text: String) {
        if (messages.lastOrNull()?.role == "assistant") {
            val old = messages.removeAt(messages.lastIndex)
            messages.add(old.copy(text = old.text + text))
        } else messages.add(ChatLine("assistant", text))
    }
    fun consume(event: JSONObject) {
        val sequence = event.optLong("seq")
        if (sequence > 0 && sequence <= lastSequence) return
        lastSequence = maxOf(sequence, lastSequence)
        when (event.optString("type")) {
            "user" -> messages.add(ChatLine("user", event.optString("text")))
            "error" -> { messages.add(ChatLine("status", event.optString("message"))); agentBusy = false }
            "complete" -> { agentBusy = false; permission = null; messages.add(ChatLine("status", "本轮任务已结束")) }
            "state" -> {
                agentBusy = event.optString("state") in listOf("running", "waiting")
                if (event.has("message")) messages.add(ChatLine("status", event.optString("message")))
            }
            "permission_resolved" -> {
                if (permission?.opt("id")?.toString() == event.opt("id")?.toString()) permission = null
            }
            "acp" -> {
                val message = event.getJSONObject("message")
                val parameters = message.optJSONObject("params") ?: JSONObject()
                when (message.optString("method")) {
                    "session/request_permission" -> { permission = message; agentBusy = true }
                    "session/update" -> {
                        val update = parameters.optJSONObject("update") ?: return
                        when (update.optString("sessionUpdate")) {
                            "agent_message_chunk" -> addAssistant(update.optJSONObject("content")?.optString("text").orEmpty())
                            "tool_call" -> messages.add(ChatLine("status", "工具：" + update.optString("title", "处理本地项目")))
                            "config_option_update" -> agent = agent?.apply { put("configOptions", update.optJSONArray("configOptions")) }
                        }
                    }
                }
            }
        }
    }
    fun send(text: String) = act {
        val id = agent?.getString("id") ?: throw IllegalStateException("请先开始对话")
        api.obj("/v1/agents/$id/prompt", "POST", JSONObject().put("text", text))
        if (chatInput == text) chatInput = ""
        agentBusy = true
    }
    fun cancel() = act {
        val id = agent?.getString("id") ?: return@act
        api.obj("/v1/agents/$id/cancel", "POST", JSONObject())
        note = "已请求取消，正在等待代理结束当前操作"
    }
    fun replyPermission(option: String?) = act {
        val pending = permission ?: return@act
        val result = JSONObject().put("outcome", if (option == null) JSONObject().put("outcome", "cancelled")
            else JSONObject().put("outcome", "selected").put("optionId", option))
        api.obj("/v1/agents/" + agent!!.getString("id") + "/reply", "POST",
            JSONObject().put("id", pending.get("id")).put("result", result))
        permission = null
    }
    fun configure(data: JSONObject) = act {
        agent = api.obj("/v1/agents/" + agent!!.getString("id") + "/configure", "POST", data)
        note = "会话设置已更新"
    }
    fun refreshGit() = act {
        val data = api.obj("/v1/projects/$projectId/git")
        gitStatus = data.optString("status"); gitDiff = data.optString("diff")
    }
    fun git(data: JSONObject) = act {
        api.obj("/v1/projects/$projectId/git", "POST", data)
        note = "Git 操作已完成"
        refreshGit(); refreshFiles()
    }
    fun openTerminal() = act {
        val value = api.obj("/v1/terminals", "POST", JSONObject().put("projectId", projectId))
        terminalId = value.getString("id"); tab = 3
    }
    fun closeTerminal() = act {
        val id = terminalId ?: return@act
        api.obj("/v1/terminals/$id/close", "POST", JSONObject())
        terminalSocket?.close(1000, "closed"); terminalSocket = null; terminalId = null
    }
    fun updateAgent() = act {
        if (dirty) throw IllegalStateException("请先保存或保留当前文件草稿")
        api.obj("/v1/updates/agent", "POST", JSONObject())
        do {
            delay(1500)
            val status = api.obj("/v1/updates/agent")
            updateMessage = status.optString("message", "正在检查更新")
        } while (status.optString("state") == "running")
        refreshSessions()
    }
    override fun onCleared() {
        agentSocket?.close(1000, "ui closed")
        terminalSocket?.close(1000, "ui closed")
        super.onCleared()
    }
}
