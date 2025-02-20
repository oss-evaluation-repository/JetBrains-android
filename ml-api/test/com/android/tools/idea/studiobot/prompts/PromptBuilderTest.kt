/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.idea.studiobot.prompts

import com.android.testutils.MockitoKt.mock
import com.android.testutils.MockitoKt.whenever
import com.android.tools.idea.studiobot.AiExcludeException
import com.android.tools.idea.studiobot.AiExcludeService
import com.android.tools.idea.studiobot.MimeType
import com.android.tools.idea.studiobot.StudioBot
import com.android.tools.idea.studiobot.prompts.impl.PromptImpl
import com.google.common.truth.Truth.assertThat
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.assertFailsWith
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class PromptBuilderTest : BasePlatformTestCase() {
  private val mockAiExcludeService: AiExcludeService = mock()
  private val mockStudioBot: StudioBot = mock()

  override fun setUp() {
    super.setUp()
    ApplicationManager.getApplication()
      .replaceService(StudioBot::class.java, mockStudioBot, testRootDisposable)
    whenever(mockStudioBot.aiExcludeService()).thenReturn(mockAiExcludeService)
    whenever(mockStudioBot.isContextAllowed(project)).thenReturn(true)
  }

  @Test
  fun buildPrompt_completePrompt() {
    val prompt =
      buildPrompt(project) {
        systemMessage { text("You are Gemini, an AI assistant for Android Studio.", emptyList()) }
        userMessage { text("Hello Gemini!", emptyList()) }
        modelMessage { text("Hello! How are you?", emptyList()) }
        userMessage { text("I am doing well, how about you?", emptyList()) }
      }
    assertThat(prompt)
      .isEqualTo(
        PromptImpl(
          listOf(
            Prompt.SystemMessage(
              listOf(
                Prompt.Message.TextChunk(
                  "You are Gemini, an AI assistant for Android Studio.",
                  emptyList(),
                )
              )
            ),
            Prompt.UserMessage(listOf(Prompt.Message.TextChunk("Hello Gemini!", emptyList()))),
            Prompt.ModelMessage(
              listOf(Prompt.Message.TextChunk("Hello! How are you?", emptyList()))
            ),
            Prompt.UserMessage(
              listOf(Prompt.Message.TextChunk("I am doing well, how about you?", emptyList()))
            ),
          )
        )
      )
  }

  @OptIn(ExperimentalEncodingApi::class)
  @Test
  fun buildPrompt_withBlob() {
    val data = ByteArray(10)
    val prompt =
      buildPrompt(project) {
        userMessage {
          text("What is in this image?", emptyList())
          blob(data, MimeType.JPEG, emptyList())
        }
      }
    assertThat(prompt.messages.size).isEqualTo(1)
    val chunks = prompt.messages.single().chunks
    assertThat(chunks.size).isEqualTo(2)
    assertThat(chunks[0]).isEqualTo(Prompt.Message.TextChunk("What is in this image?", emptyList()))
    assertThat(chunks[1]).isInstanceOf(Prompt.Message.BlobChunk::class.java)
    assertThat((chunks[1] as Prompt.Message.BlobChunk).data).isEqualTo(data)
  }

  @Test
  fun buildPrompt_promptWithCode() {
    val prompt =
      buildPrompt(project) {
        userMessage { text("Write some Kotlin code.", emptyList()) }
        modelMessage {
          code(
            """
            fun f(): Int {
              return 5
            }
            """
              .trimIndent(),
            KotlinLanguage.INSTANCE,
            emptyList(),
          )
        }
        userMessage {
          text("Does this Java code do the same thing?", emptyList())
          code(
            """
            int f() {
              return 5;
            }
            """
              .trimIndent(),
            JavaLanguage.INSTANCE,
            emptyList(),
          )
        }
      }
    assertThat(prompt)
      .isEqualTo(
        PromptImpl(
          listOf(
            Prompt.UserMessage(
              listOf(Prompt.Message.TextChunk("Write some Kotlin code.", emptyList()))
            ),
            Prompt.ModelMessage(
              listOf(
                Prompt.Message.CodeChunk(
                  """
                  fun f(): Int {
                    return 5
                  }
                  """
                    .trimIndent(),
                  KotlinLanguage.INSTANCE,
                  emptyList(),
                )
              )
            ),
            Prompt.UserMessage(
              listOf(
                Prompt.Message.TextChunk("Does this Java code do the same thing?", emptyList()),
                Prompt.Message.CodeChunk(
                  """
                  int f() {
                    return 5;
                  }
                  """
                    .trimIndent(),
                  JavaLanguage.INSTANCE,
                  emptyList(),
                ),
              )
            ),
          )
        )
      )
  }

  @Test
  fun buildPrompt_noMessagesThrowsError() {
    assertFailsWith<MalformedPromptException> { buildPrompt(project) {} }
  }

  @Test
  fun buildPrompt_wrongSystemMessageThrowsError() {
    assertFailsWith<MalformedPromptException> {
      buildPrompt(project) {
        userMessage { text("user", emptyList()) }
        modelMessage { text("model", emptyList()) }
        // System message should be the first message
        systemMessage { text("preamble", emptyList()) }
        userMessage { text("user", emptyList()) }
      }
    }
  }

  @Test
  fun buildPrompt_twoSystemMessagesThrowsError() {
    assertFailsWith<MalformedPromptException> {
      buildPrompt(project) {
        // Only one system message is allowed
        systemMessage { text("preamble", emptyList()) }
        systemMessage { text("preamble 2", emptyList()) }
        userMessage { text("user", emptyList()) }
        modelMessage { text("model", emptyList()) }
        userMessage { text("user", emptyList()) }
      }
    }
  }

  @Test
  fun buildPrompt_enforcesAiExclude() {
    myFixture.addFileToProject("/.aiexclude", "*.txt")
    val file = myFixture.addFileToProject("/file.txt", "").virtualFile

    buildPrompt(project) {
      systemMessage { text("preamble", emptyList()) }
      userMessage { text("user", emptyList()) }
      modelMessage { text("model", emptyList()) }
      userMessage { text("user", listOf(file)) }
    }

    whenever(mockAiExcludeService.isFileExcluded(project, file)).thenReturn(true)
    assertFailsWith<AiExcludeException> {
      buildPrompt(project) {
        systemMessage { text("preamble", emptyList()) }
        userMessage { text("user", emptyList()) }
        modelMessage { text("model", emptyList()) }
        userMessage { text("user", listOf(file)) }
      }
    }

    assertFailsWith<AiExcludeException> {
      buildPrompt(project) {
        systemMessage { text("preamble", emptyList()) }
        userMessage { text("user", emptyList()) }
        modelMessage { text("model", listOf(file)) }
        userMessage { text("user", emptyList()) }
      }
    }
  }

  @Test
  fun buildPrompt_withLastPrompt_addsToPrompt() {
    val basePrompt =
      buildPrompt(project) {
        systemMessage { text("You are Gemini", emptyList()) }
        userMessage { text("Hello Gemini!", emptyList()) }
        modelMessage { text("Hello! How are you?", emptyList()) }
      }
    val prompt =
      buildPrompt(project, basePrompt) {
        userMessage { text("I am doing well, how about you?", emptyList()) }
      }
    assertThat(prompt)
      .isEqualTo(
        PromptImpl(
          listOf(
            Prompt.SystemMessage(listOf(Prompt.Message.TextChunk("You are Gemini", emptyList()))),
            Prompt.UserMessage(listOf(Prompt.Message.TextChunk("Hello Gemini!", emptyList()))),
            Prompt.ModelMessage(
              listOf(Prompt.Message.TextChunk("Hello! How are you?", emptyList()))
            ),
            Prompt.UserMessage(
              listOf(Prompt.Message.TextChunk("I am doing well, how about you?", emptyList()))
            ),
          )
        )
      )
  }

  @Test
  fun buildPrompt_enforcesContextSharingSettingIfFilesUsed() {
    whenever(mockStudioBot.isContextAllowed(project)).thenReturn(false)
    val file = myFixture.addFileToProject("MyFile.kt", "").virtualFile
    try {
      buildPrompt(project) { userMessage { text("Hello", listOf(file)) } }
      fail("Expected an IllegalStateException")
    } catch (e: IllegalStateException) {
      assertThat(e.message)
        .isEqualTo(
          "User has not enabled context sharing. This setting must be checked before building a prompt that used any files as context."
        )
    }
    whenever(mockStudioBot.isContextAllowed(project)).thenReturn(true)
  }

  @Test
  fun buildPrompt_filesOkWhenContextAllowed() {
    whenever(mockStudioBot.isContextAllowed(project)).thenReturn(true)
    val file = myFixture.addFileToProject("MyFile.kt", "").virtualFile
    buildPrompt(project) { userMessage { text("Hello", listOf(file)) } }
    whenever(mockStudioBot.isContextAllowed(project)).thenReturn(false)
  }
}
