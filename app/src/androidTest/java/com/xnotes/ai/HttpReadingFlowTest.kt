package com.xnotes.ai

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.os.LocaleListCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xnotes.MainActivity
import com.xnotes.R
import com.xnotes.core.model.TextItem
import com.xnotes.ui.Editor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the shipped UI and real Android HTTP client against an in-process local server. */
@RunWith(AndroidJUnit4::class)
class HttpReadingFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun editor(): Editor = MainActivity::class.java.getDeclaredField("editor").let {
        it.isAccessible = true
        it.get(compose.activity) as Editor
    }

    @Test fun configureTestAskFollowUpSaveAndUndoOverHttp() {
        compose.waitUntil(30_000) { compose.onAllNodesWithText("xnotes").fetchSemanticsNodes().isNotEmpty() }
        val activity = compose.activity
        val ed = editor()
        val originalConfig = ed.ai.config
        val originalLocale = AppCompatDelegate.getApplicationLocales()
        LocalAiServer().use { server ->
            try {
                compose.runOnIdle {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
                    ed.ai.saveConfig(AiConfig())
                    ed.newNote()
                }
                compose.runOnIdle { ed.openAiPage() }
                compose.waitUntil(15_000) { ed.ai.visible }
                compose.onNodeWithContentDescription(activity.getString(R.string.ai_open_preferences)).performClick()
                compose.onNode(hasSetTextAction() and hasText(activity.getString(R.string.ai_endpoint)))
                    .performScrollTo().performTextInput(server.baseUrl)
                compose.onNode(hasSetTextAction() and hasText(activity.getString(R.string.ai_model_name)))
                    .performScrollTo().performTextInput("http-fixture-model")
                compose.onNode(hasSetTextAction() and hasText(activity.getString(R.string.ai_api_key)))
                    .performScrollTo().performTextInput("http-fixture-key")
                // Connection test must use unsaved form values, without page contents.
                compose.onNodeWithText(activity.getString(R.string.ai_test)).performScrollTo().performClick()
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithText(activity.getString(R.string.ai_connected)).fetchSemanticsNodes().isNotEmpty()
                }
                val test = server.nextRequest()
                assertEquals("POST /v1/chat/completions HTTP/1.1", test.line)
                assertEquals(1, test.body.getJSONArray("messages").length())
                assertFalse(test.body.toString().contains("image_url"))
                compose.onNodeWithText(activity.getString(R.string.ai_save_settings)).performScrollTo().performClick()
                compose.runOnIdle {
                    assertEquals(server.baseUrl, ed.ai.config.baseUrl)
                    ed.ai.reloadConfig()
                    assertEquals(server.baseUrl, ed.ai.config.baseUrl)
                }
                compose.onNodeWithContentDescription(activity.getString(R.string.back_to_reading)).performClick()

                val question = hasSetTextAction() and hasText(activity.getString(R.string.ai_question))
                compose.onNode(question).performTextInput("First question over HTTP")
                compose.onNodeWithText(activity.getString(R.string.ai_send)).performClick()
                compose.waitUntil(15_000) { ed.ai.turns.size == 1 }
                compose.onNodeWithText(LocalAiServer.ANSWER).assertExists()
                val sent = server.nextRequest()
                assertTrue("Actual page capture is sent", sent.body.toString().contains("image_url"))
                assertEquals("Bearer http-fixture-key", sent.headers["authorization"])
                assertFalse(sent.body.toString().contains("http-fixture-key"))

                val pages = ed.state.document.pages.size
                compose.onNodeWithText(activity.getString(R.string.ai_append_answer)).performClick()
                compose.runOnIdle {
                    assertTrue(ed.state.document.pages.size > pages)
                    assertTrue(ed.state.document.pages.last().items.filterIsInstance<TextItem>().any { it.text.contains(LocalAiServer.ANSWER) })
                    ed.undo()
                    assertEquals(pages, ed.state.document.pages.size)
                    ed.redo()
                    assertTrue(ed.state.document.pages.size > pages)
                }

                compose.onNodeWithContentDescription(activity.getString(R.string.ai_open_preferences)).performClick()
                compose.onNodeWithContentDescription(activity.getString(R.string.back_to_reading)).performClick()
                compose.onNode(question).performTextInput("Follow up over HTTP")
                compose.onNodeWithText(activity.getString(R.string.ai_send)).performClick()
                compose.waitUntil(15_000) { ed.ai.turns.size == 2 }
                val followUp = server.nextRequest()
                assertTrue(followUp.body.toString().contains("First question over HTTP"))
                assertTrue(followUp.body.toString().contains(LocalAiServer.ANSWER))
            } finally {
                compose.runOnIdle {
                    ed.ai.close()
                    ed.ai.saveConfig(originalConfig)
                    ed.goHome()
                    AppCompatDelegate.setApplicationLocales(originalLocale)
                }
            }
        }
    }
}
