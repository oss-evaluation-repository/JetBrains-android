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
package com.android.tools.idea.adddevicedialog

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.android.tools.adtui.compose.JewelTestTheme
import org.junit.Rule
import org.junit.Test

class DeviceTableTest {
  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun toggleGoogleOem() {
    composeTestRule.setContent {
      JewelTestTheme {
        val source = TestDeviceSource()
        source.apply { TestDevices.allTestDevices.forEach { add(it) } }
        DeviceTable(source.profiles)
      }
    }

    composeTestRule.onNodeWithText("Pixel 5", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithText("Pixel Fold", useUnmergedTree = true).assertIsDisplayed()

    composeTestRule.onNode(hasText("Google") and hasAnySibling(hasText("OEM"))).performClick()

    composeTestRule.onNodeWithText("Pixel 5", useUnmergedTree = true).assertDoesNotExist()
    composeTestRule.onNodeWithText("Pixel Fold", useUnmergedTree = true).assertDoesNotExist()
  }

  @Test
  fun textSearch() {
    composeTestRule.setContent {
      JewelTestTheme {
        val source = TestDeviceSource()
        source.apply { TestDevices.allTestDevices.forEach { add(it) } }
        DeviceTable(source.profiles)
      }
    }

    composeTestRule.onNode(hasSetTextAction()).performTextReplacement("sam")

    composeTestRule.onNodeWithText("Pixel 5", useUnmergedTree = true).assertDoesNotExist()
    composeTestRule.onNodeWithText("Pixel Fold", useUnmergedTree = true).assertDoesNotExist()
    composeTestRule.onNodeWithText("Galaxy S22", useUnmergedTree = true).assertIsDisplayed()
  }
}
