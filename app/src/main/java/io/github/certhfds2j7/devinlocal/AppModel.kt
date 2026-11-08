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
data class ChatLine(val role: String, val text: String, val toolId: String? = null, val files: List<JSONObject> = emptyList(),
                    val messageId: String = java.util.UUID.randomUUID().toString(), val turnId: String? = null)
data class RenameItem(val kind: String, val id: String, val name: String)

class AppModel(app: Application): AndroidViewModel(app) {
    val api = BridgeClient(app)
    private val preferences = app.getSharedPreferences("workspace", 0)
    var tab by mutableIntStateOf(0)
    var connected by mutableStateOf(false)
    var connecting by mutableStateOf(false)
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
    val pendingAttachments = mutableStateListOf<JSONObject>()
    var downloadFolder by mutableStateOf("")
    var downloadFiles by mutableStateOf<List<JSONObject>>(emptyList())
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
    private var currentTurnId: String? = null
    private val historyMutex = Mutex()
    private var reconnectJob: Job? = null
    private var chatGeneration = 0L
    private var reconnectAttempt = 0
    private val draftJobs = mutableMapOf<String, Job>()
    private val draftProjects = mutableMapOf<String, String>()
    private val draftMutex = Mutex()
    val projectId get() = selected?.optString("id").orEmpty()
    val dirty get() = filePath.isNotEmpty() && editorText != originalText

    init {
        viewModelScope.launch {
            LinkState.changes.collect { value ->
                if (value.isNotEmpty()) {
                    connectionMessage = value
                    connecting = value.startsWith("正在连接")
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
        detachChat()
        selected = value
        preferences.edit().putString("project", projectId).apply()
        folder = ""; files = emptyList(); filePath = ""; searchResults = emptyList()
        agent = null; messages.clear(); currentTurnId = null; permission = null; agentBusy = false; pendingAttachments.clear()
        act { refreshFiles(); refreshSessions() }
    }
    fun createProject(name: String, kind: String) = act {
        val created = api.obj("/v1/projects", "POST", JSONObject().put("name", name).put("kind", kind))
        projects = api.array("/v1/projects").objects()
        chooseProject(created)
        note = "项目已创建在手机本地"
    }
    fun renameItem(item: RenameItem, name: String) = act {
        if (item.kind == "project") {
            val updated = api.obj("/v1/projects/${item.id}", "PATCH", JSONObject().put("name", name))
            projects = projects.map { if (it.optString("id") == item.id) updated else it }
            if (projectId == item.id) selected = updated
            note = "项目名称已更新"
        } else {
            val updated = api.obj("/v1/agents/${item.id}", "PATCH", JSONObject().put("title", name))
            if (agent?.optString("id") == item.id) agent = updated
            refreshSessions()
            note = "对话名称已更新"
        }
    }
    fun deleteProject(item: JSONObject, confirmation: String) = act {
        val id = item.getString("id")
        api.obj("/v1/projects/$id", "DELETE", JSONObject().put("confirmName", confirmation))
        val ownedDrafts = draftProjects.filterValues { it == id }.keys.toList()
        ownedDrafts.forEach { path -> draftJobs.remove(path)?.cancel(); draftProjects.remove(path) }
        if (projectId == id && filePath.isNotEmpty()) {
            val currentDraft = draftFile()
            draftJobs.remove(currentDraft.path)?.cancel()
            withContext(Dispatchers.IO) { currentDraft.delete() }
        }
        draftMutex.withLock {
            withContext(Dispatchers.IO) {
                File(getApplication<Application>().filesDir, "drafts").listFiles()?.filter { it.isFile }?.forEach { file ->
                    val owner = try { JSONObject(file.readText()).optString("projectId") } catch (_: Exception) { "" }
                    if (owner == id || file.path in ownedDrafts) file.delete()
                }
            }
        }
        projects = projects.filterNot { it.optString("id") == id }
        if (projectId == id) {
            detachChat(); terminalSocket?.close(1000, "project deleted"); terminalSocket = null; terminalId = null
            selected = null; preferences.edit().remove("project").apply()
            agent = null; messages.clear(); currentTurnId = null; permission = null; agentBusy = false; chatInput = ""; conversations = emptyList()
            pendingAttachments.clear(); files = emptyList(); folder = ""; filePath = ""; editorText = ""; originalText = ""
            fileRevision = "missing"; conflict = false; searchResults = emptyList(); gitStatus = ""; gitDiff = ""
            downloadFiles = emptyList(); downloadFolder = ""; tab = 0
        }
        note = "项目及其本机文件和对话已删除"
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
        draftProjects[file.path] = projectId
        val data = JSONObject().put("text", text).put("revision", fileRevision).put("projectId", projectId).toString()
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
        detachChat()
        agent = metadata; agentBusy = metadata.optString("state") in listOf("running", "waiting")
        messages.clear(); currentTurnId = null; permission = null; lastSequence = 0
        val history = api.array("/v1/agents/" + metadata.getString("id") + "/history")
        history.objects().forEach { consume(it) }
        connectChat()
        refreshSessions()
        tab = 1
    }
    fun connectChat() {
        reconnectAttempt = 0
        openChat()
    }
    private fun detachChat() {
        chatGeneration++
        reconnectJob?.cancel()
        agentSocket?.cancel()
        agentSocket = null
        chatConnected = false
    }
    fun resumeConnections() {
        if (agent != null && !chatConnected) connectChat()
    }
    private fun chatDisconnected(generation: Long, status: Int? = null) {
        if (generation != chatGeneration) return
        chatConnected = false
        if (status == 409) {
            agentBusy = false
            permission = null
            note = "本地会话已中断，请从历史会话恢复"
            return
        }
        if (status == 401) {
            connected = false
            note = "本地连接凭据已失效，请重新连接环境"
            return
        }
        if (reconnectAttempt == 0) note = "对话连接中断，正在重新连接；本地任务可能仍在运行"
        val delayMillis = minOf(30_000L, 2_000L shl minOf(reconnectAttempt++, 4))
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            delay(delayMillis)
            if (generation == chatGeneration) {
                reconnectJob = null
                openChat()
            }
        }
    }
    private fun openChat() {
        val id = agent?.optString("id") ?: return
        detachChat()
        val generation = chatGeneration
        try {
        agentSocket = api.socket("/v1/agents/$id/stream?after=$lastSequence", object: WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                viewModelScope.launch {
                    if (generation == chatGeneration) {
                        chatConnected = true
                        reconnectAttempt = 0
                    } else webSocket.cancel()
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                viewModelScope.launch {
                    if (generation == chatGeneration) try {
                        val event = JSONObject(text)
                        if (event.optString("type") == "history_changed" && event.optLong("seq") > lastSequence)
                            reloadConversation(id)
                        else consume(event)
                    } catch (_: Exception) { }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                viewModelScope.launch { chatDisconnected(generation, response?.code) }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                viewModelScope.launch { chatDisconnected(generation) }
            }
        })
        } catch (error: ApiException) {
            chatDisconnected(generation, error.status)
        }
    }
    private fun addAssistant(text: String, sequence: Long) {
        if (messages.lastOrNull()?.role == "assistant" && messages.lastOrNull()?.turnId == currentTurnId) {
            val old = messages.removeAt(messages.lastIndex)
            messages.add(old.copy(text = old.text + text))
        } else messages.add(ChatLine("assistant", text, messageId = "assistant-$sequence", turnId = currentTurnId))
    }
    private fun showToolOutput(update: JSONObject) {
        if (!update.has("content")) return
        val id = update.optString("toolCallId")
        if (id.isBlank()) return
        val text = update.optJSONArray("content")?.objects()?.mapNotNull {
            val content = it.optJSONObject("content")
            if (it.optString("type") == "content" && content?.optString("type") == "text") content.optString("text") else null
        }?.joinToString("\n").orEmpty()
        if (text.isBlank()) return
        val visible = if (text.length > 24_000) text.take(24_000) + "\n…输出较长，完整内容保留在本地会话记录中" else text
        val index = messages.indexOfFirst { it.toolId == id }
        val line = ChatLine("tool", visible, id, messageId = "tool-$id", turnId = currentTurnId)
        if (index < 0) messages.add(line) else messages[index] = line
    }
    fun consume(event: JSONObject) {
        val sequence = event.optLong("seq")
        if (sequence > 0 && sequence <= lastSequence) return
        lastSequence = maxOf(sequence, lastSequence)
        when (event.optString("type")) {
            "user" -> {
                currentTurnId = event.optString("turnId").ifBlank { "legacy-$sequence" }
                messages.add(ChatLine("user", event.optString("text"), files = event.optJSONArray("attachments")?.objects().orEmpty(),
                    messageId = "user-$currentTurnId", turnId = currentTurnId))
            }
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
                            "agent_message_chunk" -> {
                                val content = update.optJSONObject("content") ?: return
                                when (content.optString("type")) {
                                    "text" -> addAssistant(content.optString("text"), sequence)
                                    "resource_link" -> messages.add(ChatLine("assistant", "", files = listOf(content), messageId = "resource-$sequence", turnId = currentTurnId))
                                    "resource" -> content.optJSONObject("resource")?.let {
                                        messages.add(ChatLine("assistant", "", files = listOf(it), messageId = "resource-$sequence", turnId = currentTurnId))
                                    }
                                }
                            }
                            "tool_call" -> {
                                messages.add(ChatLine("status", "工具：" + update.optString("title", "处理本地项目")))
                                showToolOutput(update)
                            }
                            "tool_call_update" -> showToolOutput(update)
                            "config_option_update" -> agent = agent?.let { JSONObject(it.toString()).put("configOptions", update.optJSONArray("configOptions")) }
                            "current_mode_update" -> agent = agent?.let {
                                val copy = JSONObject(it.toString())
                                copy.put("modes", (copy.optJSONObject("modes") ?: JSONObject()).put("currentModeId", update.optString("currentModeId")))
                            }
                        }
                    }
                }
            }
        }
    }
    fun send(text: String) = act {
        val id = agent?.getString("id") ?: throw IllegalStateException("请先开始对话")
        val attachments = pendingAttachments.toList()
        api.obj("/v1/agents/$id/prompt", "POST", JSONObject().put("text", text)
            .put("attachments", JSONArray(attachments.map { it.getString("id") })))
        pendingAttachments.removeAll(attachments.toSet())
        if (chatInput == text) chatInput = ""
        agentBusy = true
    }
    private suspend fun reloadConversation(id: String) = historyMutex.withLock {
        if (agent?.optString("id") != id) return@withLock
        detachChat()
        val rows = api.array("/v1/agents/$id/history")
        if (agent?.optString("id") != id) return@withLock
        messages.clear(); currentTurnId = null; permission = null; lastSequence = 0
        rows.objects().forEach { consume(it) }
        connectChat()
    }
    fun deleteTurn(sessionId: String, turnId: String) = act {
        api.obj("/v1/agents/$sessionId/turns/" + encoded(turnId), "DELETE")
        reloadConversation(sessionId)
        note = "已删除本机这一轮问答记录，代理已有上下文未改变"
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
    suspend fun uploadAttachment(name: String, bytes: ByteArray) {
        if (projectId.isEmpty()) throw IllegalStateException("请先选择项目")
        if (pendingAttachments.size >= 8) throw IllegalStateException("每次最多附加 8 个文件")
        val pid = projectId
        val result = JSONObject(String(api.bytes("/v1/projects/$pid/attachments?name=" + encoded(name), "POST", bytes, "application/octet-stream")))
        if (pid == projectId) pendingAttachments.add(result)
    }
    fun refreshDownloads(folder: String = "") = act {
        downloadFiles = api.array("/v1/projects/$projectId/files?path=" + encoded(folder)).objects()
        downloadFolder = folder
    }
    fun prepareDownload(path: String, ready: (File, String) -> Unit) = act {
        val data = api.bytes("/v1/projects/$projectId/download?path=" + encoded(path), limit = 50 * 1024 * 1024)
        val uri = android.net.Uri.parse(path)
        val name = (if (uri.scheme == "file") uri.lastPathSegment else path.substringAfterLast('/'))?.ifBlank { null } ?: "下载文件"
        val cache = File(getApplication<Application>().cacheDir, "download-" + java.util.UUID.randomUUID())
        withContext(Dispatchers.IO) { cache.writeBytes(data) }
        ready(cache, name)
    }
    fun deleteConversation(item: JSONObject) = act {
        val id = item.getString("id")
        api.obj("/v1/agents/$id", "DELETE")
        if (agent?.optString("id") == id) {
            detachChat(); agent = null; messages.clear(); currentTurnId = null; permission = null; agentBusy = false; lastSequence = 0
        }
        refreshSessions()
        note = "已删除这条本机对话记录"
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
        detachChat()
        terminalSocket?.close(1000, "ui closed")
        super.onCleared()
    }
}
