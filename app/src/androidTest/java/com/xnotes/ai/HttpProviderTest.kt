package com.xnotes.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HttpProviderTest {
    @Test fun androidHttpConnectionTestAndConversationUseTheConfiguredEndpoint() = runBlocking {
        LocalAiServer().use { server ->
            val config = AiConfig(server.baseUrl, "fixture-model", "fixture-key")
            assertEquals("OK", AiProtocol.answer(withTimeout(10_000) { AiHttp.complete(config, AiProtocol.connectionTestRequest(config)) }))
            val test = server.nextRequest()
            assertEquals("POST /v1/chat/completions HTTP/1.1", test.line)
            assertEquals("Bearer fixture-key", test.headers["authorization"])
            assertEquals(1, test.body.getJSONArray("messages").length())
            assertFalse(test.body.toString().contains("image_url"))
            assertFalse(test.body.toString().contains("fixture-key"))

            val fullEndpoint = config.copy(baseUrl = "${server.baseUrl}/chat/completions/")
            val request = AiProtocol.request(fullEndpoint, AiSource("HTTP source", "笔记内容", "fixture-image"),
                listOf(AiTurn("prior question", "prior answer")), "follow up")
            assertEquals(LocalAiServer.ANSWER, AiProtocol.answer(withTimeout(10_000) { AiHttp.complete(fullEndpoint, request) }))
            val sent = server.nextRequest()
            assertEquals("POST /v1/chat/completions HTTP/1.1", sent.line)
            assertTrue(sent.body.toString().contains("笔记内容"))
            assertTrue(sent.body.toString().contains("image_url"))
            assertTrue(sent.body.toString().contains("prior answer"))
        }
    }

    @Test fun httpAuthFailureRedirectAndEmptyAnswerAreReported() = runBlocking {
        LocalAiServer().use { server ->
            val config = AiConfig(server.baseUrl, "fixture-model", "fixture-key")
            val request = AiProtocol.connectionTestRequest(config)
            server.replies.put(LocalAiServer.Reply(401, "{}"))
            val auth = runCatching { withTimeout(10_000) { AiHttp.complete(config, request) } }.exceptionOrNull()
            assertTrue(auth is IllegalArgumentException)
            assertTrue(auth?.message.orEmpty().contains("API Key"))
            server.nextRequest()

            server.replies.put(LocalAiServer.Reply(302, "{}", "${server.baseUrl}/redirect-target"))
            val redirect = runCatching { withTimeout(10_000) { AiHttp.complete(config, request) } }.exceptionOrNull()
            assertTrue(redirect?.message.orEmpty().contains("302"))
            assertEquals("POST /v1/chat/completions HTTP/1.1", server.nextRequest().line)
            assertTrue("Redirects must not forward credentials", server.requests.isEmpty())

            server.replies.put(LocalAiServer.Reply(200, LocalAiServer.answer("")))
            val empty = runCatching { AiProtocol.answer(withTimeout(10_000) { AiHttp.complete(config, request) }) }.exceptionOrNull()
            assertTrue(empty?.message.orEmpty().contains("空回答"))
        }
    }

    @Test fun pendingHttpRequestCanBeCancelled() = runBlocking {
        LocalAiServer().use { server ->
            val config = AiConfig(server.baseUrl, "fixture-model", "fixture-key")
            server.replies.put(LocalAiServer.Reply(200, LocalAiServer.answer("late answer"), delayMs = 750))
            val pending = async { AiHttp.complete(config, AiProtocol.connectionTestRequest(config)) }
            withContext(Dispatchers.IO) { server.nextRequest() }
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
        }
    }
}
