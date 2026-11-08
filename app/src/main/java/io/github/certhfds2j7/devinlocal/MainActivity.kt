package io.github.certhfds2j7.devinlocal

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.viewModels
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

class MainActivity: ComponentActivity() {
    private var recoverPairing = true
    private val model: AppModel by viewModels()
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (ContextCompat.checkSelfPermission(this, TermuxLauncher.PERMISSION) == PackageManager.PERMISSION_GRANTED)
            connect()
        else model.note = "需要允许连接 Termux，才能访问手机上的项目和工具"
    }
    private fun connect() {
        if (ContextCompat.checkSelfPermission(this, TermuxLauncher.PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            val requested = mutableListOf(TermuxLauncher.PERMISSION)
            if (Build.VERSION.SDK_INT >= 33) requested.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.launch(requested.toTypedArray())
        } else try { TermuxLauncher.launch(this) }
        catch (error: Exception) {
            model.note = if (error is IllegalStateException) error.message.orEmpty() else "请先安装并初始化 Termux，再开启应用连接"
        }
    }
    override fun onPostResume() {
        super.onPostResume()
        model.resumeConnections()
        if (recoverPairing && LinkState.token(this) == null &&
            ContextCompat.checkSelfPermission(this, TermuxLauncher.PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            recoverPairing = false
            connect()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!AppUpdates.schedule(this)) model.note = "系统暂时无法安排自动更新，请在设置中手动检查"
        val preferences = getSharedPreferences("updates", 0)
        if (System.currentTimeMillis() - preferences.getLong("checked", 0) > 24 * 60 * 60 * 1000L) {
            lifecycleScope.launch {
                try {
                    model.appUpdate = AppUpdates.check(this@MainActivity)
                    preferences.edit().putLong("checked", System.currentTimeMillis()).apply()
                } catch (_: Exception) { }
            }
        }
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFF8DD5D2), secondary = Color(0xFFB0CCC9))
                else lightColorScheme(primary = Color(0xFF167D7F), secondary = Color(0xFF506B69),
                    background = Color(0xFFF5F8F8), surface = Color(0xFFF9FCFC))) {
                ClientApp(model, ::connect)
            }
        }
    }
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ClientApp(vm: AppModel, onConnect: () -> Unit) {
        var createDialog by remember { mutableStateOf(false) }
        var historyDialog by remember { mutableStateOf(false) }
        var gitDialog by remember { mutableStateOf(false) }
        var configDialog by remember { mutableStateOf(false) }
        var newFileDialog by remember { mutableStateOf(false) }
        var closeTerminalDialog by remember { mutableStateOf(false) }
        var downloadDialog by remember { mutableStateOf(false) }
        var deleteDialog by remember { mutableStateOf<JSONObject?>(null) }
        var cachedDownload by rememberSaveable { mutableStateOf("") }
        val saveDownload = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
            val cached = cachedDownload; cachedDownload = ""
            if (cached.isNotBlank()) vm.act {
                withContext(Dispatchers.IO) {
                    val file = File(cached)
                    require(file.canonicalFile.parentFile == cacheDir.canonicalFile && file.name.startsWith("download-"))
                    try {
                        if (uri != null) {
                            val output = contentResolver.openOutputStream(uri) ?: error("无法写入选择的位置")
                            output.use { target -> file.inputStream().use { it.copyTo(target) } }
                        }
                    } finally { file.delete() }
                }
                if (uri != null) vm.note = "文件已保存到选择的位置"
            }
        }
        val download: (String) -> Unit = { path ->
            if (cachedDownload.isBlank() && vm.operations == 0)
                vm.prepareDownload(path) { file, name -> cachedDownload = file.absolutePath; saveDownload.launch(name) }
            else vm.note = "请先完成当前传输或保存操作"
        }
        val uploadFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            val destinationProject = vm.projectId
            if (uris.isNotEmpty()) vm.act {
                if (uris.size + vm.pendingAttachments.size > 8) error("每次最多附加 8 个文件")
                for (uri in uris) {
                    val (name, data) = withContext(Dispatchers.IO) {
                        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        } ?: uri.lastPathSegment ?: "附件"
                        name to readLimited(uri, 20 * 1024 * 1024)
                    }
                    check(vm.projectId == destinationProject) { "项目已切换，请在目标项目中重新上传" }
                    vm.uploadAttachment(name, data)
                }
                vm.note = "文件已上传到当前项目，可随消息发送"
            }
        }
        val snackbar = remember { SnackbarHostState() }
        val importZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) vm.act {
                val bytes = withContext(Dispatchers.IO) { readLimited(uri, 50 * 1024 * 1024) }
                vm.api.bytes("/v1/projects/${vm.projectId}/archive", "POST", bytes, "application/zip")
                vm.refreshFiles(); vm.note = "项目文件已导入"
            }
        }
        val exportZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) vm.act {
                val bytes = vm.api.bytes("/v1/projects/${vm.projectId}/archive")
                withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
                vm.note = "项目已导出"
            }
        }
        LaunchedEffect(vm.note) {
            if (vm.note.isNotBlank()) {
                val text = vm.note; vm.note = ""; snackbar.showSnackbar(text)
            }
        }
        Scaffold(
            topBar = { TopAppBar(title = {
                Column {
                    Text("Devin 本地助手", fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
                    Text("非官方客户端 · 手机本地执行", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }, actions = {
                TextButton(onClick = { if (vm.connected) vm.refresh() else onConnect() }, enabled = !vm.connecting) {
                    Text(if (vm.connecting) "连接中…" else if (vm.connected) "● 已连接" else "连接环境")
                }
            }) },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar {
                    val labels = listOf("项目", "对话", "编辑", "终端", "设置")
                    val symbols = listOf("▦", "◌", "〈〉", ">_", "⚙")
                    labels.forEachIndexed { index, label ->
                        NavigationBarItem(selected = vm.tab == index, onClick = { vm.tab = index },
                            icon = { Text(symbols[index], fontSize = 19.sp) }, label = { Text(label) })
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                if (vm.operations > 0 || vm.connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
                when (vm.tab) {
                    0 -> Page {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("在手机上编程", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                Text("项目、编辑和命令留在手机上，AI 使用你的官方账号联网。", style = MaterialTheme.typography.bodyMedium)
                                if (!vm.connected) {
                                    Text(vm.connectionMessage, fontWeight = FontWeight.SemiBold)
                                    if (vm.connecting) Text("首次连接需要安装本地组件，可能需要几分钟。请保持网络和 Termux 运行。", fontSize = 12.sp)
                                    Button(onClick = onConnect, enabled = !vm.connecting) { Text(if (vm.connecting) "正在准备本地环境…" else "连接本地环境") }
                                }
                            }
                        }
                        if (!vm.connected) SetupGuide(onConnect, vm.connecting)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("我的项目", style = MaterialTheme.typography.titleMedium)
                            FilledTonalButton(onClick = { createDialog = true }, enabled = vm.connected) { Text("＋ 新建项目") }
                        }
                        if (vm.projects.isEmpty() && vm.connected) Text("从一个 Python 脚本或网站开始，也可以导入已有项目。")
                        vm.projects.forEach { item ->
                            val current = vm.projectId == item.optString("id")
                            OutlinedCard(modifier = Modifier.fillMaxWidth().clickable {
                                if (vm.terminalId != null && !current) vm.note = "切换项目前请先关闭当前终端"
                                else vm.chooseProject(item)
                            }, border = BorderStroke(if (current) 2.dp else 1.dp,
                                if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(item.optString("name"), fontWeight = FontWeight.SemiBold)
                                    Text(when (item.optString("kind")) { "python" -> "Python 项目"; "web" -> "网站项目"; else -> "通用项目" },
                                        style = MaterialTheme.typography.bodySmall)
                                    if (current) Text("当前工作项目", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                                }
                            }
                        }
                        if (vm.selected != null) {
                            Text("当前项目：${vm.selected!!.optString("name")}", fontWeight = FontWeight.SemiBold)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { vm.startChat() }) { Text("开始对话") }
                                OutlinedButton(onClick = { vm.tab = 2 }) { Text("浏览文件") }
                                OutlinedButton(onClick = { vm.refreshGit(); gitDialog = true }) { Text("Git 与修改对比") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { importZip.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("导入 ZIP") }
                                TextButton(onClick = { exportZip.launch(vm.selected!!.optString("name") + ".zip") }) { Text("导出项目") }
                            }
                            Text("导入已有项目时，请先创建“空项目”。Git 远程认证可在本地终端完成。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    1 -> {
                        ProjectHeading(vm)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { vm.startChat() }, enabled = vm.connected && vm.projectId.isNotEmpty()) { Text("新对话") }
                            TextButton(onClick = { vm.act { vm.refreshSessions() }; historyDialog = true }, enabled = vm.connected) { Text("历史会话") }
                            TextButton(onClick = { configDialog = true }, enabled = vm.agent != null && !vm.agentBusy) { Text("模型与思考") }
                            TextButton(onClick = { vm.refreshDownloads(); downloadDialog = true }, enabled = vm.selected != null) { Text("下载文件") }
                            if (vm.agent != null && !vm.chatConnected) TextButton(onClick = { vm.connectChat() }) { Text("重新连接") }
                        }
                        if (vm.agent != null) Column(Modifier.fillMaxWidth().clickable(enabled = !vm.agentBusy) { configDialog = true }
                            .padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Text("模型：" + currentConfigLabel(vm.agent, "model"), fontSize = 12.sp, maxLines = 2)
                            Text("模式：" + currentConfigLabel(vm.agent, "mode") + " · 思考：" + currentConfigLabel(vm.agent, "thought_level"), fontSize = 12.sp)
                        }
                        if (vm.agent == null) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(if (vm.projectId.isEmpty()) "先在“项目”中选择一个项目" else "开始新对话，或恢复历史会话",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else key(vm.projectId, vm.agent?.optString("id")) {
                            RichChatPanel(vm, Modifier.weight(1f).fillMaxWidth()) { link ->
                                val scheme = Uri.parse(link).scheme
                                when (scheme?.lowercase()) {
                                    "http", "https" -> openUrl(link)
                                    null, "file" -> download(link)
                                    else -> vm.note = "此链接不属于可下载的项目文件"
                                }
                            }
                        }
                        if (vm.agentBusy) Text("代理正在处理任务…", Modifier.padding(horizontal = 16.dp), fontSize = 12.sp)
                        if (vm.pendingAttachments.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                            vm.pendingAttachments.toList().forEach { item ->
                                TextButton(onClick = { vm.pendingAttachments.remove(item) }) { Text(item.optString("name") + " ×") }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Bottom) {
                            TextButton(onClick = { uploadFiles.launch(arrayOf("*/*")) }, enabled = vm.selected != null && vm.operations == 0) { Text("附件") }
                            OutlinedTextField(value = vm.chatInput, onValueChange = { vm.chatInput = it }, modifier = Modifier.weight(1f),
                                placeholder = { Text("描述你想完成的任务") }, maxLines = 4)
                            if (vm.agentBusy) OutlinedButton(onClick = { vm.cancel() }) { Text("取消") }
                            else Button(onClick = { vm.send(vm.chatInput) }, enabled = vm.agent != null && vm.operations == 0 && (vm.chatInput.isNotBlank() || vm.pendingAttachments.isNotEmpty())) { Text("发送") }
                        }
                    }
                    2 -> {
                        ProjectHeading(vm)
                        if (vm.filePath.isEmpty()) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(onClick = { vm.enterFolder(vm.folder.substringBeforeLast("/", "")) }, enabled = vm.folder.isNotEmpty()) { Text("‹ 上一级") }
                                TextButton(onClick = { newFileDialog = true }, enabled = vm.selected != null) { Text("新建文件") }
                            }
                            var query by rememberSaveable { mutableStateOf("") }
                            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(query, { query = it; if (it.isBlank()) vm.searchResults = emptyList() },
                                    modifier = Modifier.weight(1f), placeholder = { Text("搜索项目文件内容") }, singleLine = true)
                                TextButton(onClick = { vm.search(query) }, enabled = vm.selected != null) { Text("搜索") }
                            }
                            Text(if (vm.folder.isEmpty()) "项目文件" else vm.folder, Modifier.padding(16.dp), fontSize = 12.sp)
                            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                                if (vm.searchResults.isNotEmpty()) items(vm.searchResults) { item ->
                                    ListItem(headlineContent = { Text(item.optString("path") + ":" + item.optInt("line")) },
                                        supportingContent = { Text(item.optString("text"), maxLines = 2) },
                                        modifier = Modifier.clickable { vm.openFile(item.getString("path")) })
                                } else items(vm.files) { item ->
                                    ListItem(headlineContent = { Text((if (item.optBoolean("directory")) "▸  " else "    ") + item.optString("name")) },
                                        supportingContent = { if (!item.optBoolean("directory")) Text("${item.optLong("size")} 字节") },
                                        modifier = Modifier.clickable {
                                            if (item.optBoolean("directory")) vm.enterFolder(item.getString("path")) else vm.openFile(item.getString("path"))
                                        })
                                }
                            }
                        } else {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { vm.filePath = "" }) { Text("‹ 文件") }
                                Text(vm.filePath + if (vm.dirty) " •" else "", Modifier.weight(1f), maxLines = 1, fontSize = 12.sp)
                                Button(onClick = { vm.saveFile() }, enabled = vm.dirty && !vm.agentBusy) { Text("保存") }
                            }
                            if (vm.conflict) Card(Modifier.fillMaxWidth().padding(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("文件已发生变化，当前草稿已保留。")
                                    TextButton(onClick = { vm.discardDraftAndReload() }) { Text("放弃草稿并重新读取") }
                                }
                            }
                            CodeEditor(vm, Modifier.weight(1f).fillMaxWidth())
                        }
                    }
                    3 -> {
                        ProjectHeading(vm)
                        if (vm.terminalId == null) Page {
                            Text("手机本地终端", style = MaterialTheme.typography.titleLarge)
                            Text("在当前项目中运行 Python、Node.js、Git 和项目命令。")
                            Button(onClick = { vm.openTerminal() }, enabled = vm.connected && vm.selected != null) { Text("打开终端") }
                        } else {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                listOf("Ctrl+C" to "\u0003", "Tab" to "\t", "Esc" to "\u001b", "↑" to "\u001b[A", "↓" to "\u001b[B").forEach { (label, key) ->
                                    TextButton(onClick = { vm.terminalSocket?.send(JSONObject().put("type", "input").put("data", key).toString()) }) { Text(label) }
                                }
                                TextButton(onClick = { closeTerminalDialog = true }) { Text("关闭") }
                            }
                            key(vm.terminalId) { TerminalPanel(vm, Modifier.weight(1f).fillMaxWidth()) }
                        }
                    }
                    4 -> SettingsPage(vm, onConnect)
                }
            }
        }
        if (createDialog) {
            var name by remember { mutableStateOf("") }
            var kind by remember { mutableStateOf("python") }
            AlertDialog(onDismissRequest = { createDialog = false }, title = { Text("新建本地项目") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("项目名称") }, singleLine = true)
                    listOf("python" to "Python 项目", "web" to "网站项目", "empty" to "空项目（导入或克隆）").forEach { (value, label) ->
                        Row(Modifier.fillMaxWidth().clickable { kind = value }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(kind == value, onClick = { kind = value }); Text(label)
                        }
                    }
                }
            }, confirmButton = { Button(onClick = { vm.createProject(name, kind); createDialog = false }, enabled = name.isNotBlank()) { Text("创建") } },
                dismissButton = { TextButton(onClick = { createDialog = false }) { Text("取消") } })
        }
        if (newFileDialog) {
            var path by remember { mutableStateOf(if (vm.folder.isEmpty()) "" else vm.folder + "/") }
            AlertDialog(onDismissRequest = { newFileDialog = false }, title = { Text("新建文件") },
                text = { OutlinedTextField(path, { path = it }, label = { Text("项目内路径，例如 src/main.py") }) },
                confirmButton = { Button(onClick = { vm.createFile(path); newFileDialog = false }, enabled = path.isNotBlank()) { Text("创建") } },
                dismissButton = { TextButton(onClick = { newFileDialog = false }) { Text("取消") } })
        }
        if (historyDialog) AlertDialog(onDismissRequest = { historyDialog = false }, title = { Text("历史会话") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                if (vm.conversations.isEmpty()) Text("当前项目还没有历史会话")
                vm.conversations.forEach { item ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(modifier = Modifier.weight(1f), onClick = { historyDialog = false; vm.startChat(item) }) {
                        Text(item.optString("title", "对话") + " · " + stateName(item.optString("state")))
                    }
                    TextButton(onClick = { deleteDialog = item }, enabled = item.optString("state") !in listOf("running", "waiting")) { Text("删除") }
                    }
                }
            } }, confirmButton = { TextButton(onClick = { historyDialog = false }) { Text("关闭") } })
        deleteDialog?.let { item -> AlertDialog(onDismissRequest = { deleteDialog = null }, title = { Text("删除这条对话？") },
            text = { Text("将删除本客户端保存的“${item.optString("title", "对话") }”记录，无法在本客户端撤销。项目文件会保留。") },
            confirmButton = { TextButton(onClick = { vm.deleteConversation(item); deleteDialog = null }) { Text("删除记录") } },
            dismissButton = { TextButton(onClick = { deleteDialog = null }) { Text("取消") } }) }
        if (downloadDialog) AlertDialog(onDismissRequest = { downloadDialog = false }, title = { Text("下载项目文件") }, text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(vm.downloadFolder.ifBlank { "项目根目录" }, fontSize = 12.sp)
                if (vm.downloadFolder.isNotEmpty()) TextButton(onClick = { vm.refreshDownloads(vm.downloadFolder.substringBeforeLast("/", "")) }) { Text("‹ 上一级") }
                vm.downloadFiles.forEach { item ->
                    TextButton(onClick = {
                        if (item.optBoolean("directory")) vm.refreshDownloads(item.getString("path"))
                        else { downloadDialog = false; download(item.getString("path")) }
                    }) { Text((if (item.optBoolean("directory")) "▸ " else "↓ ") + item.optString("name")) }
                }
                if (vm.downloadFiles.isEmpty() && vm.operations == 0) Text("此目录暂无文件")
            }
        }, confirmButton = { TextButton(onClick = { downloadDialog = false }) { Text("关闭") } })
        if (gitDialog) GitDialog(vm) { gitDialog = false }
        if (configDialog) ConfigDialog(vm) { configDialog = false }
        if (closeTerminalDialog) AlertDialog(onDismissRequest = { closeTerminalDialog = false }, title = { Text("关闭当前终端？") },
            text = { Text("此终端中的进程会停止，已保存的项目文件会保留。") },
            confirmButton = { TextButton(onClick = { vm.closeTerminal(); closeTerminalDialog = false }) { Text("关闭终端") } },
            dismissButton = { TextButton(onClick = { closeTerminalDialog = false }) { Text("继续使用") } })
        vm.permission?.let { PermissionDialog(it, vm::replyPermission) }
    }

    private fun stateName(value: String) = when(value) {
        "running" -> "处理中"; "waiting" -> "等待确认"; "ready" -> "可继续"; "interrupted" -> "已中断"; else -> "已结束"
    }
    @Composable private fun ProjectHeading(vm: AppModel) {
        Text(vm.selected?.optString("name") ?: "尚未选择项目", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    }
    @Composable private fun Page(content: @Composable ColumnScope.() -> Unit) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
    @Composable private fun SetupGuide(connect: () -> Unit, connecting: Boolean) {
        OutlinedCard {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("首次连接", fontWeight = FontWeight.SemiBold)
                Text("1  安装并初始化 Termux\n2  在 Termux 开启应用连接\n3  允许连接权限并启动环境")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { openUrl("https://github.com/termux/termux-app/releases") }) { Text("安装 Termux") }
                    TextButton(onClick = {
                        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Termux 连接设置",
                            "mkdir -p ~/.termux; printf '\\nallow-external-apps=true\\n' >> ~/.termux/termux.properties; termux-reload-settings"))
                        model.note = "已复制连接设置，请在 Termux 粘贴并运行一次"
                    }) { Text("复制连接设置") }
                    TextButton(onClick = { openTermux() }) { Text("打开 Termux") }
                }
                Text("已完成本机初始化时，直接点击连接环境。", style = MaterialTheme.typography.bodySmall)
                Button(onClick = connect, enabled = !connecting) { Text(if (connecting) "正在连接…" else "连接环境") }
            }
        }
    }
    private fun openTermux() {
        val intent = packageManager.getLaunchIntentForPackage("com.termux")
        if (intent != null) startActivity(intent) else model.note = "请先安装 Termux"
    }
    private fun openUrl(url: String) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    private fun readLimited(uri: Uri, limit: Int): ByteArray {
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法读取所选文件" }
            val out = ByteArrayOutputStream(); val buffer = ByteArray(32768)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (out.size() + count > limit) throw Exception("所选文件过大，请在本地终端处理")
                out.write(buffer, 0, count)
            }
            return out.toByteArray()
        }
    }
    @Composable private fun SettingsPage(vm: AppModel, connect: () -> Unit) {
        var apkPath by rememberSaveable { mutableStateOf("") }
        var appStatus by rememberSaveable { mutableStateOf("") }
        var downloadProgress by remember { mutableIntStateOf(-1) }
        var licenses by remember { mutableStateOf(false) }
        val importApk = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) vm.act {
                val file = withContext(Dispatchers.IO) {
                    val destination = File(cacheDir, "updates/import.apk")
                    destination.parentFile?.mkdirs()
                    destination.writeBytes(readLimited(uri, 150 * 1024 * 1024))
                    AppUpdates.verify(this@MainActivity, destination)
                    destination
                }
                apkPath = file.absolutePath; appStatus = "离线安装包校验通过，可以安装更新"
            }
        }
        Page {
            Text("设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Section("本地环境") {
                Text(vm.connectionMessage)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = connect, enabled = !vm.connecting) { Text(if (vm.connecting) "正在连接…" else "启动 / 重新连接") }
                    TextButton(onClick = { openTermux() }) { Text("打开 Termux") }
                }
                TextButton(onClick = {
                    try { TermuxLauncher.login(this@MainActivity); openTermux() }
                    catch (_: Exception) { vm.note = "请先允许连接 Termux，再打开官方登录" }
                }) { Text("官方账号登录") }
            }
            Section("应用更新") {
                Text("当前版本 ${BuildConfig.VERSION_NAME}")
                Text("中文界面和本地服务随应用更新，安装前会核对版本与签名。", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.act {
                        vm.appUpdate = AppUpdates.check(this@MainActivity)
                        appStatus = if (vm.appUpdate == null) "当前没有可用的新版应用" else "发现新版本：" + vm.appUpdate!!.optString("versionName")
                    } }) { Text("检查应用更新") }
                    TextButton(onClick = { importApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) }) { Text("导入新版 APK") }
                }
                vm.appUpdate?.let { update ->
                    Text(update.optString("notes", "有新版本可用"))
                    Button(onClick = { vm.act {
                        downloadProgress = 0
                        try {
                            val file = AppUpdates.download(this@MainActivity, update) { progress -> runOnUiThread { downloadProgress = progress } }
                            apkPath = file.absolutePath; appStatus = "安装包校验通过"
                        } finally { downloadProgress = -1 }
                    } }, enabled = downloadProgress < 0) { Text(if (downloadProgress >= 0) "下载中 $downloadProgress%" else "下载新版本") }
                }
                if (appStatus.isNotEmpty()) Text(appStatus, fontSize = 13.sp)
                if (apkPath.isNotEmpty()) Button(onClick = {
                    try { AppUpdates.install(this@MainActivity, File(apkPath)) }
                    catch (error: Exception) { vm.note = error.message.orEmpty() }
                }) { Text("安装已校验的更新") }
            }
            Section("Devin 核心更新") {
                Text("官方代理单独更新，完成启动和本地协议检查后才切换。")
                Text("更新前请结束任务并关闭终端。", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { vm.updateAgent() }, enabled = vm.connected && !vm.agentBusy) { Text("更新 Devin 核心") }
                if (vm.updateMessage.isNotEmpty()) Text(vm.updateMessage, fontSize = 13.sp)
            }
            Section("关于") {
                Text("Devin 本地助手是独立维护的非官方客户端，与 Cognition 官方没有隶属关系。")
                Text("项目和命令在手机运行；发送给模型的内容按官方账号服务处理。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { openUrl("https://github.com/" + AppUpdates.REPOSITORY) }) { Text("源码与中文说明") }
                TextButton(onClick = { licenses = true }) { Text("开源组件许可") }
            }
        }
        if (licenses) AlertDialog(onDismissRequest = { licenses = false }, title = { Text("开源组件许可") },
            text = {
                val text = remember { assets.open("THIRD_PARTY_LICENSES.txt").bufferedReader().use { it.readText() } }
                SelectionContainer { Text(text, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), fontSize = 11.sp) }
            }, confirmButton = { TextButton(onClick = { licenses = false }) { Text("关闭") } })
    }
    @Composable private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                content()
            }
        }
    }
    @Composable private fun GitDialog(vm: AppModel, close: () -> Unit) {
        var message by rememberSaveable { mutableStateOf("") }
        var url by rememberSaveable { mutableStateOf("") }
        var name by rememberSaveable { mutableStateOf("") }
        var email by rememberSaveable { mutableStateOf("") }
        AlertDialog(onDismissRequest = close, title = { Text("Git 与修改对比") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(vm.gitStatus.ifBlank { "工作区暂无已跟踪文件的修改" }, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { vm.git(JSONObject().put("action", "stage")) }) { Text("暂存全部") }
                    TextButton(onClick = { vm.git(JSONObject().put("action", "pull")) }) { Text("拉取") }
                    TextButton(onClick = { vm.git(JSONObject().put("action", "push")) }) { Text("推送") }
                    TextButton(onClick = { vm.refreshGit() }) { Text("刷新") }
                }
                OutlinedTextField(message, { message = it }, label = { Text("提交说明") })
                Button(onClick = { vm.git(JSONObject().put("action", "commit").put("message", message)) }, enabled = message.isNotBlank()) { Text("提交已暂存修改") }
                HorizontalDivider()
                Text("提交身份（当前项目）", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(name, { name = it }, label = { Text("姓名") }, singleLine = true)
                OutlinedTextField(email, { email = it }, label = { Text("邮箱") }, singleLine = true)
                TextButton(onClick = { vm.git(JSONObject().put("action", "identity").put("name", name).put("email", email)) }) { Text("保存提交身份") }
                OutlinedTextField(url, { url = it }, label = { Text("HTTPS / SSH 仓库地址") })
                TextButton(onClick = { vm.git(JSONObject().put("action", "clone").put("url", url)) }, enabled = url.isNotBlank()) { Text("克隆到当前空项目") }
                Text("修改对比", fontWeight = FontWeight.SemiBold)
                SelectionContainer { Text(vm.gitDiff.ifBlank { "暂无差异" }, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
            }
        }, confirmButton = { TextButton(onClick = close) { Text("完成") } })
    }
    @Composable private fun ConfigDialog(vm: AppModel, close: () -> Unit) {
        val metadata = vm.agent ?: return
        var category by remember { mutableStateOf("model") }
        val config = metadata.optJSONArray("configOptions")?.objects()?.firstOrNull { configCategory(it) == category }
        AlertDialog(onDismissRequest = close, title = { Text("模型、模式与思考强度") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("model" to "模型", "mode" to "模式", "thought_level" to "思考强度").forEach { (value, label) ->
                        TextButton(onClick = { category = value }) { Text((if (category == value) "● " else "") + label) }
                    }
                }
                Text("当前：" + currentConfigLabel(metadata, category), fontWeight = FontWeight.SemiBold)
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    if (config != null) {
                        configValues(config).filterNot { category == "mode" && unsafeMode(it.optString("value")) }.forEach { option ->
                            val value = option.getString("value")
                            Row(Modifier.fillMaxWidth().clickable(enabled = !vm.agentBusy && vm.operations == 0) {
                                vm.configure(JSONObject().put("configId", config.getString("id")).put("value", value))
                            }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = config.optString("currentValue") == value, onClick = null)
                                Text(optionLabel(category, value, option.optString("name", value)), Modifier.padding(vertical = 10.dp))
                            }
                        }
                    } else if (category == "thought_level") Text("当前模型未提供可调思考强度；可切换模型后再次查看。")
                    else {
                        val legacy = metadata.optJSONObject(if (category == "model") "models" else "modes")
                        legacy?.optJSONArray(if (category == "model") "availableModels" else "availableModes")?.objects()?.forEach { option ->
                            val value = option.optString(if (category == "model") "modelId" else "id")
                            if (category != "mode" || !unsafeMode(value)) TextButton(onClick = {
                                vm.configure(JSONObject().put(if (category == "model") "modelId" else "modeId", value))
                            }, enabled = vm.operations == 0 && !vm.agentBusy) { Text(optionLabel(category, value, option.optString("name", value))) }
                        }
                    }
                }
                Text("选项由官方代理和当前模型提供，切换后立即生效。", fontSize = 12.sp)
            }
        }, confirmButton = { TextButton(onClick = close) { Text("关闭") } })
    }
    private fun unsafeMode(value: String) = listOf("bypass", "dangerous", "yolo", "autonomous").any { value.contains(it, true) }
    @Composable private fun PermissionDialog(message: JSONObject, reply: (String?) -> Unit) {
        val parameters = message.getJSONObject("params")
        val tool = parameters.optJSONObject("toolCall") ?: JSONObject()
        AlertDialog(onDismissRequest = { reply(null) }, title = { Text("代理申请工具权限") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tool.optString("title", "处理本地项目"), fontWeight = FontWeight.SemiBold)
                    SelectionContainer { Text(tool.toString(2), fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                    parameters.getJSONArray("options").objects().forEach { option ->
                        val kind = option.optString("kind")
                        val label = when(kind) {
                            "allow_once" -> "仅允许这一次"; "reject_once" -> "拒绝这一次"
                            "allow_always" -> "记住允许：" + option.optString("name")
                            "reject_always" -> "记住拒绝：" + option.optString("name")
                            else -> option.optString("name")
                        }
                        OutlinedButton(onClick = { reply(option.getString("optionId")) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                    }
                }
            }, confirmButton = {}, dismissButton = { TextButton(onClick = { reply(null) }) { Text("取消此请求") } })
    }
}
