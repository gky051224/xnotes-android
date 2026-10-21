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
import com.xnotes.R
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

@Composable
fun AiToolbarButton(editor: Editor) {
    IconButton(onClick = { if (editor.ai.visible) editor.ai.close() else editor.openAiPage() }) {
        Icon(Icons.Outlined.AutoAwesome, contentDescription = stringResource(R.string.ai_assistant))
    }
}

@Composable
fun AiWorkspace(editor: Editor, modifier: Modifier = Modifier, onOpenPreferences: () -> Unit, content: @Composable () -> Unit) {
    val ai = editor.ai
    val scope = rememberCoroutineScope()
    val draft = remember(ai.source) { AiQuestionDraft() }
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
                AiContent(editor, draft, list, send, onOpenPreferences, docked = true,
                    modifier = Modifier.width(360.dp).fillMaxHeight())
            }
        }
        if (ai.visible && !docked) {
            Dialog(onDismissRequest = ai::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                AiContent(editor, draft, list, send, onOpenPreferences, docked = false,
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
    editor: Editor, draft: AiQuestionDraft,
    list: LazyListState, send: () -> Unit, onOpenPreferences: () -> Unit, docked: Boolean, modifier: Modifier,
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
                    Text(stringResource(R.string.ai_assistant), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 10.dp).weight(1f))
                    IconButton(onClick = onOpenPreferences, enabled = !ai.loading) {
                        Icon(Icons.Outlined.Settings, stringResource(R.string.ai_open_preferences))
                    }
                    IconButton(onClick = ai::close) { Icon(Icons.Outlined.Close, stringResource(R.string.ai_close)) }
                }
                Text(ai.source?.label.orEmpty(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    maxLines = if (compactHeight) 1 else 2, overflow = TextOverflow.Ellipsis)
                if (!compactHeight) Text(
                    if (ai.source?.imageBase64 != null && ai.config.vision) stringResource(R.string.ai_scope_image) else stringResource(R.string.ai_scope_text),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                )
                if (docked && !compactHeight) {
                    TextButton(onClick = { editor.openAiPage() }, enabled = !ai.loading) {
                        Text(stringResource(R.string.ai_new_chat))
                    }
                }
                HorizontalDivider()
                if (ai.config.apiKey.isBlank()) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 18.dp)) {
                        Text(stringResource(R.string.ai_connect_first), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.ai_connect_guidance), modifier = Modifier.padding(top = 10.dp))
                        Button(onClick = onOpenPreferences, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.ai_go_preferences)) }
                    }
                } else {
                    if (ai.turns.isEmpty()) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 18.dp)) {
                            Text(stringResource(R.string.ai_empty_title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.ai_empty_description), modifier = Modifier.padding(top = 10.dp))
                            val text = ai.source?.text.orEmpty()
                            if (text.isNotBlank()) {
                                Text(stringResource(R.string.ai_source_preview), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 22.dp))
                                SelectionContainer { Text(text.take(600), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                            } else {
                                Text(stringResource(R.string.ai_image_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 22.dp))
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
                                                .setPrimaryClip(ClipData.newPlainText(context.getString(R.string.ai_note_label), turn.answer))
                                        }) { Text(stringResource(R.string.ai_copy)) }
                                        TextButton(onClick = { ai.saveAnswer(index) }, enabled = !ai.isSaved(index)) {
                                            Text(if (ai.isSaved(index)) stringResource(R.string.ai_saved_answer) else stringResource(R.string.ai_append_answer))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (!compactHeight) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            stringResource(R.string.ai_summarize) to stringResource(R.string.ai_summarize_prompt),
                            stringResource(R.string.ai_explain) to stringResource(R.string.ai_explain_prompt),
                            stringResource(R.string.ai_quiz) to stringResource(R.string.ai_quiz_prompt),
                        ).forEach { (label, prompt) -> AssistChip(onClick = { draft.question = prompt }, label = { Text(label) }, enabled = !ai.loading) }
                    }
                    OutlinedTextField(
                        value = draft.question, onValueChange = { if (it.length <= 4_000) draft.question = it },
                        label = { Text(stringResource(R.string.ai_question)) }, modifier = Modifier.fillMaxWidth(),
                        minLines = if (compactHeight) 1 else 2, maxLines = if (compactHeight) 3 else 4, enabled = !ai.loading,
                    )
                    Text(if (compactHeight) stringResource(R.string.ai_send_notice_short) else stringResource(R.string.ai_send_notice), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                        if (ai.loading) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.ai_thinking), Modifier.padding(horizontal = 10.dp))
                            TextButton(onClick = ai::stop) { Text(stringResource(R.string.ai_stop)) }
                        } else {
                            Text(ai.config.model, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                            Button(onClick = send, enabled = draft.question.isNotBlank()) { Text(stringResource(R.string.ai_send)) }
                        }
                    }
                }
                ai.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            }
            }
        }
}

@Composable
private fun AiSettings(ai: AiAssistant, draft: AiSettingsDraft, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var passed by remember { mutableStateOf(false) }
    var testJob by remember { mutableStateOf<Job?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.ai_connect_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.ai_connect_description), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(draft.url, { draft.url = it; result = null }, label = { Text(stringResource(R.string.ai_endpoint)) }, placeholder = { Text(stringResource(R.string.ai_endpoint_hint)) }, singleLine = true, enabled = !testing, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.model, { draft.model = it; result = null }, label = { Text(stringResource(R.string.ai_model_name)) }, singleLine = true, enabled = !testing, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.key, { draft.key = it; result = null }, label = { Text(stringResource(R.string.ai_api_key)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !testing, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ai_attach_image))
                Text(stringResource(R.string.ai_text_only), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = draft.vision, onCheckedChange = { draft.vision = it; result = null }, enabled = !testing)
        }
        Text(stringResource(R.string.ai_key_storage), style = MaterialTheme.typography.bodySmall)
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
                        result = context.getString(R.string.ai_connected)
                    } catch (_: TimeoutCancellationException) { result = context.getString(R.string.ai_timeout) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { result = if (e is IllegalArgumentException) e.message else context.getString(R.string.ai_connect_failed) }
                    finally { testing = false }
                }
            }, enabled = !testing) { Text(if (testing) stringResource(R.string.ai_testing) else stringResource(R.string.ai_test)) }
            if (testing) {
                TextButton(onClick = { testJob?.cancel(); testing = false }) { Text(stringResource(R.string.ai_stop_test)) }
            }
        }
        Text(stringResource(R.string.ai_test_notice), style = MaterialTheme.typography.bodySmall)
        result?.let { Text(it, color = if (passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (ai.saveConfig(AiConfig(draft.url, draft.model, draft.key, draft.vision))) {
                    passed = true
                    result = context.getString(R.string.ai_config_saved)
                }
            }, enabled = !testing) { Text(stringResource(R.string.ai_save_settings)) }
            TextButton(onClick = {
                if (ai.saveConfig(AiConfig())) {
                    draft.url = ""; draft.model = ""; draft.key = ""; draft.vision = true
                    passed = true
                    result = context.getString(R.string.ai_config_cleared)
                }
            }, enabled = !testing) { Text(stringResource(R.string.ai_clear_config)) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun AiPreferencesSection(ai: AiAssistant) {
    val draft = remember(ai.config) { AiSettingsDraft(ai.config) }
    LaunchedEffect(ai) {
        ai.error = null
        ai.reloadConfig()
        draft.url = ai.config.baseUrl
        draft.model = ai.config.model
        draft.key = ai.config.apiKey
        draft.vision = ai.config.vision
    }
    AiSettings(ai, draft, Modifier.widthIn(max = 600.dp).fillMaxWidth())
    ai.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
