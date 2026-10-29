package com.xnotes.ai

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiProtocolTest {
    private val config = AiConfig("https://example.com/v1/", "vision-model", "test-key")

    @Test fun connectionTestUsesOnlyMinimalTextWithoutCredentialsOrSources() {
        val json = JSONObject(AiProtocol.connectionTestRequest(config))
        val messages = json.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("请只回复 OK。", messages.getJSONObject(0).getString("content"))
        assertFalse(json.toString().contains("test-key"))
        assertFalse(json.toString().contains("image_url"))
    }

    @Test fun connectionTestRequiresCompleteProviderSettings() {
        assertThrows(IllegalArgumentException::class.java) { AiProtocol.connectionTestRequest(config.copy(apiKey = "")) }
        assertThrows(IllegalArgumentException::class.java) { AiProtocol.connectionTestRequest(config.copy(model = "")) }
    }

    @Test fun endpointAcceptsBaseOrFullPath() {
        assertEquals("https://example.com/v1/chat/completions", config.endpoint())
        assertEquals("https://example.com/custom/chat/completions", config.copy(baseUrl = "https://example.com/custom/chat/completions/").endpoint())
    }

    @Test fun endpointRejectsCredentialsUnsupportedSchemesAndQuery() {
        listOf("ftp://example.com/v1", "file:///v1", "https://user:pass@example.com", "https://example.com?key=x", "https://example.com/#key", "not-a-url").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { config.copy(baseUrl = url).endpoint() }
        }
    }

    @Test fun httpEndpointsKeepSchemeHostPortAndCustomPath() {
        mapOf(
            "http://192.168.1.10:8000/v1/" to "http://192.168.1.10:8000/v1/chat/completions",
            "http://localhost:11434/v1" to "http://localhost:11434/v1/chat/completions",
            "http://example.com/custom/chat/completions/" to "http://example.com/custom/chat/completions",
            "http://[::1]:8000/v1" to "http://[::1]:8000/v1/chat/completions",
            " HTTP://example.com/v1 " to "HTTP://example.com/v1/chat/completions",
        ).forEach { (input, expected) -> assertEquals(expected, config.copy(baseUrl = input).endpoint()) }
    }

    @Test fun endpointRejectsMalformedHostsAndPorts() {
        listOf("http://", "http://:8000/v1", "http://example.com:0/v1", "http://example.com:65536/v1",
            "http://example.com:-1/v1", "http://example.com:/v1", "http://example.com:abc/v1",
            "http://example.com/v1\nInjected: value").forEach { url ->
            assertThrows("Should reject $url", IllegalArgumentException::class.java) { config.copy(baseUrl = url).endpoint() }
        }
    }

    @Test fun httpWorksForBothConnectionTestAndImageConversation() {
        val http = config.copy(baseUrl = "http://192.168.1.10:8080/v1/")
        assertEquals(1, JSONObject(AiProtocol.connectionTestRequest(http)).getJSONArray("messages").length())
        val request = JSONObject(AiProtocol.request(http, AiSource("fixture", "text", "image"), emptyList(), "question"))
        assertTrue(request.toString().contains("image_url"))
        assertFalse(request.toString().contains("test-key"))
    }

    @Test fun requestAttachesImageAndOnlyTheLastFourTurns() {
        val history = (1..8).map { AiTurn("q$it", "a$it") }
        val json = JSONObject(AiProtocol.request(config, AiSource("书 · 第 8 页", "来源文字", "image-data"), history, "总结"))
        val messages = json.getJSONArray("messages")
        assertEquals(12, messages.length())
        assertEquals("q5", messages.getJSONObject(3).getString("content"))
        assertEquals("总结", messages.getJSONObject(11).getString("content"))
        val source = messages.getJSONObject(1).getJSONArray("content")
        assertEquals("data:image/jpeg;base64,image-data", source.getJSONObject(1).getJSONObject("image_url").getString("url"))
        assertFalse(json.toString().contains("test-key"))
    }

    @Test fun disablingVisionOmitsImageEvenWhenCaptured() {
        val json = JSONObject(AiProtocol.request(config.copy(vision = false), AiSource("PDF 文字", "可提取文字", "private-image"), emptyList(), "解释"))
        assertFalse(json.toString().contains("private-image"))
        assertTrue(json.getJSONArray("messages").getJSONObject(1).getString("content").contains("可提取文字"))
    }

    @Test fun imageOnlySourceRequiresVision() {
        assertThrows(IllegalArgumentException::class.java) {
            AiProtocol.request(config.copy(vision = false), AiSource("手写", imageBase64 = "image"), emptyList(), "总结")
        }
    }

    @Test fun longSourceIsBoundedAndBlankQuestionRejected() {
        val json = JSONObject(AiProtocol.request(config, AiSource("p1", "x".repeat(100_000)), emptyList(), "总结"))
        assertTrue(json.toString().length < 26_000)
        assertThrows(IllegalArgumentException::class.java) { AiProtocol.request(config, AiSource("p1", "text"), emptyList(), " ") }
    }

    @Test fun parsesStringAndMultipartAnswers() {
        assertEquals("你好", AiProtocol.answer("""{"choices":[{"message":{"content":"你好"}}]}"""))
        assertEquals("第一段\n第二段", AiProtocol.answer("""{"choices":[{"message":{"content":[{"type":"text","text":"第一段"},{"type":"text","text":"第二段"}]}}]}"""))
    }

    @Test fun rejectsMalformedEmptyAndOversizedAnswers() {
        listOf("bad json", "{}", """{"choices":[{"message":{"content":null}}]}""").forEach { body ->
            assertThrows(IllegalArgumentException::class.java) { AiProtocol.answer(body) }
        }
        val body = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("message", JSONObject().put("content", "x".repeat(40_000))))).toString()
        assertThrows(IllegalArgumentException::class.java) { AiProtocol.answer(body) }
    }

    @Test fun truncatedResponseIsMarkedAndErrorsAreActionable() {
        assertTrue(AiProtocol.answer("""{"choices":[{"finish_reason":"length","message":{"content":"部分回答"}}]}""").contains("长度限制"))
        assertTrue(AiProtocol.httpError(401).contains("API Key"))
        assertTrue(AiProtocol.httpError(429).contains("额度"))
    }

    @Test fun paginatorPreservesAllContentAndRespectsMeasuredLimit() {
        val text = (1..200).joinToString("\n") { "第 $it 行：复习内容" }
        val pages = AiNotePaginator.paginate(text) { it.length <= 90 }
        assertEquals(text, pages.joinToString(""))
        assertTrue(pages.size > 1)
        assertTrue(pages.all { it.length <= 90 })
    }

    @Test fun paginatorDoesNotSplitEmoji() {
        val text = "😀".repeat(15)
        val pages = AiNotePaginator.paginate(text) { it.length <= 5 }
        assertEquals(text, pages.joinToString(""))
        assertTrue(pages.all { it.length == 4 || it.length == 2 })
    }

    @Test fun paginatorRejectsUnusablePageInsteadOfLooping() {
        assertThrows(IllegalArgumentException::class.java) { AiNotePaginator.paginate("text") { false } }
        assertTrue(AiNotePaginator.paginate("") { true }.isEmpty())
    }
}
