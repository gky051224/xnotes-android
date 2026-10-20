package com.xnotes.ai

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

class AiAssistant(context: Context) {
    private val preferences = AiPreferences(context)
    var config by mutableStateOf(AiConfig())
        private set
    var visible by mutableStateOf(false)
        private set
    var source by mutableStateOf<AiSource?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var settingsOpen by mutableStateOf(false)
    var job: Job? = null
    val turns = mutableStateListOf<AiTurn>()
    private var valid: () -> Boolean = { false }
    private var save: (String) -> Unit = {}
    private val saved = mutableSetOf<Int>()
    private var requestVersion = 0

    init {
        runCatching { preferences.load() }.onSuccess { config = it }.onFailure {
            error = "无法读取旧的模型配置，请重新填写"
        }
    }

    fun open(source: AiSource, valid: () -> Boolean, save: (String) -> Unit) {
        close()
        this.source = source
        this.valid = valid
        this.save = save
        visible = true
        settingsOpen = config.apiKey.isBlank()
    }

    fun close() {
        requestVersion++
        job?.cancel()
        job = null
        loading = false
        visible = false
        source = null
        turns.clear()
        saved.clear()
        valid = { false }
        save = {}
        error = null
    }

    fun saveConfig(value: AiConfig): Boolean = try {
        preferences.save(value)
        config = value.copy(apiKey = value.apiKey.trim())
        settingsOpen = false
        error = null
        true
    } catch (e: Exception) {
        error = if (e is IllegalArgumentException) e.message else "模型配置保存失败，请重试"
        false
    }

    suspend fun ask(question: String) {
        if (loading) return
        val held = source ?: return
        if (!valid()) { error = "来源笔记已关闭或切换，请重新打开 AI 助手"; return }
        error = null
        loading = true
        val version = ++requestVersion
        try {
            val body = AiProtocol.request(config, held, turns.toList(), question.trim())
            val answer = AiProtocol.answer(withTimeout(120_000) { AiHttp.complete(config, body) })
            if (source === held && valid()) turns.add(AiTurn(question.trim(), answer))
            else error = "来源笔记已切换，请重新打开 AI 助手"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = when (e) {
                is IllegalArgumentException -> e.message
                is SocketTimeoutException -> "模型响应超时，请重试或换用更快的模型"
                else -> "无法连接模型服务，请检查网络和接口地址"
            }
        } finally { if (requestVersion == version) loading = false }
    }

    fun stop() { requestVersion++; job?.cancel(); job = null; loading = false }
    fun isSaved(index: Int): Boolean = index in saved
    var saveVersion by mutableStateOf(0)
        private set

    fun saveAnswer(index: Int) {
        if (index in saved) return
        if (!valid()) { error = "来源笔记已关闭或切换，回答未写入"; return }
        val turn = turns.getOrNull(index) ?: return
        try {
            save("AI 阅读笔记\n来源：${source?.label.orEmpty()}\n\n问题：${turn.question}\n\n${turn.answer}")
            saved += index
            saveVersion++
        } catch (_: Exception) { error = "回答保存失败，可以先复制回答后重试" }
    }
}

internal object AiHttp {
    private val worker = Executors.newFixedThreadPool(2) { r -> Thread(r, "xnotes-ai").apply { isDaemon = true } }
    private const val MAX_RESPONSE = 2 * 1024 * 1024

    suspend fun complete(config: AiConfig, body: String): String = suspendCancellableCoroutine { continuation ->
        val connection = AtomicReference<HttpURLConnection?>()
        val future = worker.submit {
            var conn: HttpURLConnection? = null
            try {
                if (!continuation.isActive) return@submit
                conn = (URL(config.endpoint()).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 90_000
                    instanceFollowRedirects = false
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Authorization", "Bearer ${config.apiKey.trim()}")
                }
                connection.set(conn)
                if (!continuation.isActive) return@submit
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                require(code in 200..299) { AiProtocol.httpError(code) }
                val bytes = ByteArrayOutputStream()
                conn.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (continuation.isActive) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        require(bytes.size() + n <= MAX_RESPONSE) { "模型返回内容过大，请缩小范围后重试" }
                        bytes.write(buffer, 0, n)
                    }
                }
                if (continuation.isActive) continuation.resume(bytes.toString("UTF-8"))
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
            } finally { conn?.disconnect(); connection.set(null) }
        }
        continuation.invokeOnCancellation { connection.getAndSet(null)?.disconnect(); future.cancel(true) }
    }
}
