/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.tools.idea.nav.safeargs.psi.xml

import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.impl.source.xml.XmlTagImpl
import com.intellij.testFramework.ApplicationRule
import com.intellij.ui.IconManager
import com.intellij.ui.PlatformIcons
import org.junit.Rule
import org.junit.Test

class SafeArgsXmlTagTest {
  @get:Rule val applicationRule = ApplicationRule()

  @Test
  fun checkValueEquivalency() {
    val originalTag =
      object : XmlTagImpl() {
        override fun isPhysical() = true
      }

    val tagA =
      SafeArgsXmlTag(
        xmlTag = originalTag,
        icon = IconManager.getInstance().getPlatformIcon(PlatformIcons.Class),
        name = "Foo",
        containerIdentifier = "package1",
      )

    val tagB =
      SafeArgsXmlTag(
        xmlTag = originalTag,
        icon = IconManager.getInstance().getPlatformIcon(PlatformIcons.Class),
        name = "Foo",
        containerIdentifier = "package1",
      )

    ApplicationManager.getApplication().runReadAction {
      assertThat(tagA.isEquivalentTo(tagB)).isTrue()
      assertThat(tagA).isEqualTo(tagB)
    }
  }
}
