package com.xnotes.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xnotes.ui.Editor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

@Composable
fun AiToolbarButton(editor: Editor) {
    IconButton(onClick = { if (editor.ai.visible) editor.ai.close() else editor.openAiPage() }) {
        Icon(Icons.Outlined.AutoAwesome, contentDescription = "AI 阅读助手")
    }
}

@Composable
fun AiWorkspace(editor: Editor, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val ai = editor.ai
    val scope = rememberCoroutineScope()
    val draft = remember(ai.source) { AiQuestionDraft() }
    val settings = remember(ai.config) { AiSettingsDraft(ai.config) }
    val list = rememberLazyListState()
    // This lifecycle belongs to the editor, not to either presentation. Resizing the app
    // must not cancel a request or discard a question when the panel becomes a dialog.
    DisposableEffect(ai) { onDispose { ai.close() } }
    BackHandler(enabled = ai.visible) { ai.close() }
    LaunchedEffect(editor.canvasOpen) { if (editor.canvasOpen) ai.close() }
    LaunchedEffect(ai.turns.size, ai.loading) {
        if (ai.turns.isNotEmpty()) list.animateScrollToItem(ai.turns.lastIndex)
    }
    val send: () -> Unit = {
        val sent = draft.question
        val count = ai.turns.size
        ai.job = scope.launch {
            ai.ask(sent)
            if (ai.turns.size > count) draft.question = ""
        }
    }
    BoxWithConstraints(modifier) {
        val docked = maxWidth >= 840.dp && !editor.canvasOpen
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) { content() }
            if (ai.visible && docked) {
                VerticalDivider()
                AiContent(editor, draft, settings, list, send, docked = true,
                    modifier = Modifier.width(360.dp).fillMaxHeight())
            }
        }
        if (ai.visible && !docked) {
            Dialog(onDismissRequest = ai::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                AiContent(editor, draft, settings, list, send, docked = false,
                    modifier = Modifier.padding(16.dp).widthIn(max = 840.dp).fillMaxWidth().fillMaxHeight(0.94f))
            }
        }
    }
}

private class AiQuestionDraft { var question by mutableStateOf("") }

private class AiSettingsDraft(config: AiConfig) {
    var url by mutableStateOf(config.baseUrl)
    var model by mutableStateOf(config.model)
    var key by mutableStateOf(config.apiKey)
    var vision by mutableStateOf(config.vision)
}

@Composable
private fun AiContent(
    editor: Editor, draft: AiQuestionDraft, settings: AiSettingsDraft,
    list: LazyListState, send: () -> Unit, docked: Boolean, modifier: Modifier,
) {
    val ai = editor.ai
    val context = LocalContext.current
        Surface(
            modifier = modifier,
            shape = if (docked) RectangleShape else MaterialTheme.shapes.extraLarge,
            tonalElevation = if (docked) 1.dp else 6.dp,
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
            val compactHeight = maxHeight < 560.dp
            Column(Modifier.padding(if (docked) 16.dp else 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                    Text("AI 阅读助手", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 10.dp).weight(1f))
                    IconButton(onClick = { ai.settingsOpen = !ai.settingsOpen }, enabled = !ai.loading) {
                        Icon(Icons.Outlined.Settings, "模型设置")
                    }
                    IconButton(onClick = ai::close) { Icon(Icons.Outlined.Close, "关闭 AI 助手") }
                }
                Text(ai.source?.label.orEmpty(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    maxLines = if (compactHeight) 1 else 2, overflow = TextOverflow.Ellipsis)
                if (!compactHeight) Text(
                    if (ai.source?.imageBase64 != null && ai.config.vision) "范围：当前页或框选内容（含图片），并非整本书" else "范围：本次选取的文字，并非整本书",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                )
                if (docked && !compactHeight) {
                    TextButton(onClick = { editor.openAiPage() }, enabled = !ai.loading) {
                        Text("新对话 · 使用当前页")
                    }
                }
                HorizontalDivider()
                if (ai.settingsOpen) {
                    AiSettings(ai, settings, Modifier.weight(1f))
                } else {
                    if (ai.turns.isEmpty()) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 18.dp)) {
                            Text("边读边问，让想法留在笔记里", style = MaterialTheme.typography.titleMedium)
                            Text("可以解释难点、整理重点，或根据这一页生成复习题。回答可保存为笔记末尾的新页，并支持撤销。", modifier = Modifier.padding(top = 10.dp))
                            val text = ai.source?.text.orEmpty()
                            if (text.isNotBlank()) {
                                Text("来源文字预览", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 22.dp))
                                SelectionContainer { Text(text.take(600), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                            } else {
                                Text("本次使用页面图片识别手写或扫描内容，需要支持图片输入的模型。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 22.dp))
                            }
                        }
                    } else {
                        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            itemsIndexed(ai.turns) { index, turn ->
                                Column {
                                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large) {
                                        Text(turn.question, Modifier.padding(12.dp))
                                    }
                                    SelectionContainer { Text(turn.answer, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyLarge) }
                                    Row {
                                        TextButton(onClick = {
                                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                                .setPrimaryClip(ClipData.newPlainText("AI 阅读笔记", turn.answer))
                                        }) { Text("复制") }
                                        TextButton(onClick = { ai.saveAnswer(index) }, enabled = !ai.isSaved(index)) {
                                            Text(if (ai.isSaved(index)) "已追加到笔记" else "追加到笔记")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (!compactHeight) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "总结重点" to "请总结这份内容的核心观点和重点，使用简洁的条目。",
                            "解释难点" to "请找出这份内容中较难理解的概念，用通俗语言和例子解释。",
                            "生成复习题" to "请基于这份内容生成 3 道复习题，将参考答案放在题目之后。",
                        ).forEach { (label, prompt) -> AssistChip(onClick = { draft.question = prompt }, label = { Text(label) }, enabled = !ai.loading) }
                    }
                    OutlinedTextField(
                        value = draft.question, onValueChange = { if (it.length <= 4_000) draft.question = it },
                        label = { Text("针对这份内容提问") }, modifier = Modifier.fillMaxWidth(),
                        minLines = if (compactHeight) 1 else 2, maxLines = if (compactHeight) 3 else 4, enabled = !ai.loading,
                    )
                    Text(if (compactHeight) "发送所选内容至你配置的模型服务" else "发送时，所选内容与本轮对话会提交到你配置的模型服务。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                        if (ai.loading) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("正在思考…", Modifier.padding(horizontal = 10.dp))
                            TextButton(onClick = ai::stop) { Text("停止") }
                        } else {
                            Text(ai.config.model, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                            Button(onClick = send, enabled = draft.question.isNotBlank()) { Text("发送") }
                        }
                    }
                }
                ai.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            }
            }
        }
}

@Composable
private fun AiSettings(ai: AiAssistant, draft: AiSettingsDraft, modifier: Modifier, onDone: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var passed by remember { mutableStateOf(false) }
    var testJob by remember { mutableStateOf<Job?>(null) }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(4.dp))
        Text("连接你的模型", style = MaterialTheme.typography.titleMedium)
        Text("支持 Chat Completions 格式的兼容接口。手写和扫描页需要支持图片输入的模型。", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(draft.url, { draft.url = it; result = null }, label = { Text("接口地址") }, placeholder = { Text("https://你的服务商/v1") }, singleLine = true, enabled = !testing, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.model, { draft.model = it; result = null }, label = { Text("模型名称") }, singleLine = true, enabled = !testing, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.key, { draft.key = it; result = null }, label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !testing, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("附带页面图片")
                Text("关闭后只发送提取的文字", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = draft.vision, onCheckedChange = { draft.vision = it })
        }
        Text("API Key 在本机加密保存，不会写入笔记或随系统备份同步。", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                testJob = scope.launch {
                    testing = true
                    result = null
                    passed = false
                    try {
                        val config = AiConfig(draft.url, draft.model, draft.key, draft.vision)
                        AiProtocol.answer(withTimeout(30_000) { AiHttp.complete(config, AiProtocol.connectionTestRequest(config)) })
                        passed = true
                        result = "连接成功，文字对话接口可用"
                    } catch (_: TimeoutCancellationException) { result = "连接超时，请检查地址和网络" }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { result = if (e is IllegalArgumentException) e.message else "无法连接，请检查网络和接口地址" }
                    finally { testing = false }
                }
            }, enabled = !testing) { Text(if (testing) "测试中…" else "测试连接") }
            if (testing) {
                TextButton(onClick = { testJob?.cancel(); testing = false }) { Text("停止测试") }
            }
        }
        Text("测试只发送一句文字，不会上传笔记；图片能力需在阅读时验证。", style = MaterialTheme.typography.bodySmall)
        result?.let { Text(it, color = if (passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (ai.saveConfig(AiConfig(draft.url, draft.model, draft.key, draft.vision))) onDone?.invoke() }, enabled = !testing) { Text("保存设置") }
            TextButton(onClick = { if (ai.saveConfig(AiConfig())) onDone?.invoke() }, enabled = !testing) { Text("清除配置") }
            TextButton(onClick = { if (onDone != null) onDone() else ai.settingsOpen = false }) { Text("返回") }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun AiProviderSettings(ai: AiAssistant, onClose: () -> Unit) {
    val draft = remember(ai.config) { AiSettingsDraft(ai.config) }
    LaunchedEffect(ai) { ai.error = null; ai.reloadConfig() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 600.dp).fillMaxWidth().fillMaxHeight(0.94f), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("AI 模型", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "关闭模型设置") }
                }
                AiSettings(ai, draft, Modifier.weight(1f), onDone = onClose)
                ai.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
