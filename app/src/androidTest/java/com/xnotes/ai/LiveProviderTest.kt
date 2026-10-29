package com.xnotes.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in live smoke test. Credentials arrive once from a loopback broker, never from source/files. */
@RunWith(AndroidJUnit4::class)
class LiveProviderTest {
    @Test fun connectionTextFollowUpAndVision() = runBlocking {
        val configUrl = InstrumentationRegistry.getArguments().getString("provider_config_url")
        assumeTrue("Live provider test requires an explicit ephemeral configuration broker", configUrl != null)
        val url = URL(configUrl!!)
        require(url.protocol == "http" && url.host == "10.0.2.2")
        val broker = url.openConnection() as HttpURLConnection
        val config = try {
            broker.connectTimeout = 10_000
            broker.readTimeout = 10_000
            broker.instanceFollowRedirects = false
            val json = JSONObject(broker.inputStream.bufferedReader().use { it.readText() })
            AiConfig(json.getString("baseUrl"), json.getString("model"), json.getString("key"))
        } finally { broker.disconnect() }

        suspend fun ask(label: String, body: String): String {
            val start = SystemClock.elapsedRealtime()
            val answer = AiProtocol.answer(withTimeout(45_000) { AiHttp.complete(config, body) })
            report(label, answer.replace(config.apiKey, "[REDACTED]"), SystemClock.elapsedRealtime() - start)
            return answer
        }

        val connection = ask("connection", AiProtocol.connectionTestRequest(config))
        assertTrue("Connection test should return OK", connection.contains("OK", ignoreCase = true))
        val source = AiSource("匿名测试资料", "青岚项目预算为 1800 元，每份材料价格 60 元。")
        val question = "根据资料，最多能买多少份材料？只回答数字。"
        val first = ask("text", AiProtocol.request(config.copy(vision = false), source, emptyList(), question))
        assertTrue("Expected 30 units from fixture source", first.contains("30"))
        val follow = ask("follow_up", AiProtocol.request(config.copy(vision = false), source,
            listOf(AiTurn(question, first)), "如果从预算中预留 300 元应急，现在最多买多少份？只回答数字。"))
        assertTrue("Expected 25 units after reserve", follow.contains("25"))

        // The prompt never contains the random-looking code; a response must read the actual image.
        val bitmap = Bitmap.createBitmap(320, 160, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawText("Q7M4", 35f, 105f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 64f })
        val bytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it); it.toByteArray() }
        bitmap.recycle()
        val image = Base64.encodeToString(bytes, Base64.NO_WRAP)
        try {
            val answer = ask("vision", AiProtocol.request(config, AiSource("image capability check", imageBase64 = image),
                emptyList(), "请只读出图片中的四位校验码。看不到图片就明确说看不到，不要猜。"))
            report("vision_supported", answer.contains("Q7M4", ignoreCase = true).toString(), 0)
        } catch (e: Exception) {
            report("vision_supported", "false: ${e.message.orEmpty().replace(config.apiKey, "[REDACTED]")}", 0)
        }
    }

    private fun report(label: String, answer: String, elapsedMs: Long) {
        val safe = JSONObject().put("test", label).put("answer", answer.take(1200)).put("elapsed_ms", elapsedMs)
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("live_result", safe.toString()) })
    }
}
