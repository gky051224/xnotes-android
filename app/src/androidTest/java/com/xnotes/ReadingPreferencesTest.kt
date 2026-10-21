package com.xnotes

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.os.LocaleListCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xnotes.ai.AiConfig
import com.xnotes.ai.AiSource
import com.xnotes.ai.AiTurn
import com.xnotes.ui.Editor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic history exercises session retention without contacting a model service. */
@RunWith(AndroidJUnit4::class)
class ReadingPreferencesTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    // The editor is activity-owned; reflection is confined to fixture setup and assertions.
    private fun editor(): Editor = MainActivity::class.java.getDeclaredField("editor").let {
        it.isAccessible = true
        it.get(compose.activity) as Editor
    }

    @Test fun historyAndDraftSurvivePreferencesAndLanguageChanges() {
        compose.waitUntil(30_000) { compose.onAllNodesWithText("xnotes").fetchSemanticsNodes().isNotEmpty() }
        val activity = compose.activity
        val originalLocale = AppCompatDelegate.getApplicationLocales()
        val ed = editor()
        val originalConfig = ed.ai.config
        try {
            compose.runOnIdle {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
                ed.newNote()
                assertTrue(ed.ai.saveConfig(AiConfig("https://example.invalid/v1", "test-model", "test-key")))
                ed.ai.open(AiSource("Retention test source", "Fixture text"), { ed.noteOpen }, {})
                ed.ai.turns.add(AiTurn("retained question", "retained answer"))
            }
            val document = ed.state.document
            compose.onNode(hasSetTextAction() and hasText("Ask about this source")).performTextInput("keep my draft")
            compose.onNodeWithContentDescription("AI models in Preferences").performClick()
            compose.onNodeWithText("简体中文").performClick()
            compose.waitUntil(10_000) { activity.getString(R.string.preferences) == "偏好设置" }
            compose.runOnIdle {
                assertSame("Language changes must not recreate the reading activity", activity, compose.activity)
                assertSame(document, ed.state.document)
                assertTrue(ed.noteOpen)
                assertTrue(ed.ai.visible)
                assertEquals(listOf(AiTurn("retained question", "retained answer")), ed.ai.turns.toList())
            }
            compose.onNodeWithContentDescription(activity.getString(R.string.back_to_reading)).performClick()
            compose.onNodeWithText("retained answer").assertExists()
            compose.onNode(hasSetTextAction() and hasText(activity.getString(R.string.ai_question))).assertTextContains("keep my draft")

            // Switching back also keeps the exact same document and session.
            compose.onNodeWithContentDescription(activity.getString(R.string.ai_open_preferences)).performClick()
            compose.onNodeWithText("English").performClick()
            compose.waitUntil(10_000) { activity.getString(R.string.preferences) == "Preferences" }
            compose.onNodeWithContentDescription("Back to reading").performClick()
            compose.onNodeWithText("retained answer").assertExists()
            compose.onNode(hasSetTextAction() and hasText("Ask about this source")).assertTextContains("keep my draft")
            compose.runOnIdle { assertSame(document, ed.state.document) }
            compose.onNodeWithContentDescription("AI models in Preferences").performClick()
            compose.onNodeWithText("System default").performClick()
            compose.runOnIdle { assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty) }
            compose.onNodeWithContentDescription(activity.getString(R.string.back_to_reading)).performClick()
            compose.onNodeWithText("retained answer").assertExists()
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
