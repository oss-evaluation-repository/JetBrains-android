/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.tools.idea.stats

import com.android.tools.adtui.swing.FakeUi
import com.android.tools.idea.Option
import com.android.tools.idea.Survey
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Ref
import com.intellij.util.ui.UIUtil
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.verify
import java.awt.Dimension
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.SwingUtilities
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val TEST_SURVEY : Survey = Survey.newBuilder().apply {
  title = "Test Title"
  question = "Test Question"
  intervalDays = 365
  answerCount = 2
  addOptions(Option.newBuilder().apply {
    label = "Option 0"
  })
  addOptions(Option.newBuilder().apply {
    label = "Option 1"
  })
  addOptions(Option.newBuilder().apply {
    label = "Option 2"
  })
}.build()

class MultipleChoiceDialogTest {

  lateinit var disposable: Disposable

  @Before
  fun setUp() {
    disposable = Disposer.newDisposable(this::class.simpleName!!)
  }

  @After
  fun tearDown() {
    SwingUtilities.invokeAndWait { Disposer.dispose(disposable) }
  }

  @Suppress("UnstableApiUsage")
  @Test
  fun testOK() {
    val result = Ref.create<MultipleChoiceDialog>()
    val logger = Mockito.mock(ChoiceLogger::class.java)
    SwingUtilities.invokeAndWait {
      val dialog = createDialog(logger)
      result.set(dialog)
    }
    val dialog = result.get()
    val content = getContent(dialog)
    val checkBoxes = UIUtil.findComponentsOfType(content, JCheckBox::class.java)
    assertFalse(dialog.isOKActionEnabled)
    for (button in checkBoxes) {
      assertFalse(button.isSelected)
    }

    val fakeUi = FakeUi(content)
    clickCheckBox(fakeUi, checkBoxes[0])
    assertTrue(checkBoxes[0].isSelected)
    assertFalse(dialog.isOKActionEnabled)

    clickCheckBox(fakeUi, checkBoxes[1])
    assertTrue(checkBoxes[1].isSelected)
    assertTrue(dialog.isOKActionEnabled)

    SwingUtilities.invokeAndWait { dialog.performOKAction() }
    verify(logger).log(listOf(0, 1))
  }

  @Test
  fun testCancel() {
    val result = Ref.create<MultipleChoiceDialog>()
    val logger = Mockito.mock(ChoiceLogger::class.java)
    SwingUtilities.invokeAndWait {
      val dialog = createDialog(logger)
      result.set(dialog)
    }
    val dialog = result.get()
    val content = getContent(dialog)
    val checkBoxes = UIUtil.findComponentsOfType(content, JCheckBox::class.java)
    assertFalse(dialog.isOKActionEnabled)
    for (checkBox in checkBoxes) {
      assertFalse(checkBox.isSelected)
    }

    val fakeUi = FakeUi(content)
    clickCheckBox(fakeUi, checkBoxes[0])
    assertTrue(checkBoxes[0].isSelected)
    assertFalse(dialog.isOKActionEnabled)

    SwingUtilities.invokeAndWait { dialog.doCancelAction(null) }
    verify(logger).cancel()
  }

  private fun getContent(dialog: MultipleChoiceDialog): JComponent {
    val content = dialog.content
    content.isVisible = true
    content.size = Dimension(300, 400)
    content.preferredSize = Dimension(300, 400)
    return content
  }

  private fun createDialog(logger: ChoiceLogger): MultipleChoiceDialog {
    val dialog = MultipleChoiceDialog(TEST_SURVEY, logger)
    Disposer.register(disposable, dialog.disposable)
    return dialog
  }

  private fun clickCheckBox(fakeUi: FakeUi, checkBox: JCheckBox) {
    val locationOnScreen = fakeUi.getPosition(checkBox)
    fakeUi.mouse.click(locationOnScreen.x, locationOnScreen.y)
  }
}