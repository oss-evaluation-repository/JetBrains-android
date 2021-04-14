/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.build.attribution.ui.view.details

import com.android.build.attribution.analyzers.AGPUpdateRequired
import com.android.build.attribution.analyzers.IncompatiblePluginWarning
import com.android.build.attribution.analyzers.IncompatiblePluginsDetected
import com.android.build.attribution.analyzers.NoIncompatiblePlugins
import com.android.build.attribution.data.GradlePluginsData
import com.android.build.attribution.data.PluginData
import com.android.build.attribution.ui.data.TimeWithPercentage
import com.android.build.attribution.ui.model.ConfigurationCachingRootNodeDescriptor
import com.android.build.attribution.ui.model.ConfigurationCachingWarningNodeDescriptor
import com.android.build.attribution.ui.model.WarningsDataPageModel
import com.android.build.attribution.ui.view.ViewActionHandlers
import com.android.ide.common.repository.GradleVersion
import com.android.tools.adtui.TreeWalker
import com.google.common.truth.Truth
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.EdtRule
import com.intellij.util.ui.UIUtil
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito
import javax.swing.JButton
import javax.swing.JEditorPane

class ConfigurationCacheWarningsDetailPagesFactoryTest {

  @get:Rule
  val applicationRule: ApplicationRule = ApplicationRule()

  @get:Rule
  val edtRule = EdtRule()

  private val mockHandlers = Mockito.mock(ViewActionHandlers::class.java)
  private val mockModel = Mockito.mock(WarningsDataPageModel::class.java)

  private val appPlugin = PluginData(PluginData.PluginType.BINARY_PLUGIN, "com.android.build.gradle.AppPlugin")
  private val pluginA = PluginData(PluginData.PluginType.BINARY_PLUGIN, "my.org.gradle.PluginA")
  private val pluginB = PluginData(PluginData.PluginType.BINARY_PLUGIN, "my.org.gradle.PluginB")

  @Test
  fun testConfigurationCacheAGPUpgradeRequiredPage() {
    val factory = WarningsViewDetailPagesFactory(mockModel, mockHandlers)
    val nodeDescriptor = ConfigurationCachingRootNodeDescriptor(
      AGPUpdateRequired(currentVersion = GradleVersion.parse("4.2.0"), listOf(appPlugin)),
      TimeWithPercentage(100, 1000)
    )
    val page = factory.createDetailsPage(nodeDescriptor)
    TreeWalker(page).descendants().filterIsInstance<JEditorPane>().single().text.clearHtml().let { pageHtml ->
      Truth.assertThat(pageHtml).contains("Android Gradle plugin update required to make Configuration cache available")
      Truth.assertThat(pageHtml).contains("You could save about 0.1s by")
      Truth.assertThat(pageHtml).contains("Android Gradle plugin supports Configuration cache from 7.0.0")
      Truth.assertThat(pageHtml).contains("Current version is 4.2.0")
      // Assert have applied plugins list
      Truth.assertThat(pageHtml).contains("com.android.build.gradle.AppPlugin")
    }
    TreeWalker(page).descendants().filterIsInstance<JButton>().single().let { button ->
      Truth.assertThat(button.text).isEqualTo("Update Android Gradle plugin")
    }
  }

  @Test
  fun testConfigurationCacheIncompatiblePluginDetectedPageTwoRequireUpdate() {
    val factory = WarningsViewDetailPagesFactory(mockModel, mockHandlers)
    val compatiblePluginA = GradlePluginsData.PluginInfo(
      name = "Compatible Plugin A",
      pluginClasses = listOf(pluginA.idName),
      pluginArtifact = GradlePluginsData.DependencyCoordinates("my.org", "pluginA-jar"),
      configurationCachingCompatibleFrom = GradleVersion.parse("0.2.0")
    )
    val compatiblePluginB = GradlePluginsData.PluginInfo(
      name = "Compatible Plugin B",
      pluginClasses = listOf(pluginB.idName),
      pluginArtifact = GradlePluginsData.DependencyCoordinates("my.org", "pluginB-jar"),
      configurationCachingCompatibleFrom = GradleVersion.parse("1.2.0")
    )
    val nodeDescriptor = ConfigurationCachingRootNodeDescriptor(
      IncompatiblePluginsDetected(emptyList(), listOf(
        IncompatiblePluginWarning(pluginA, GradleVersion.parse("0.1.0"), compatiblePluginA),
        IncompatiblePluginWarning(pluginB, GradleVersion.parse("1.1.0"), compatiblePluginB)
      )),
      TimeWithPercentage(100, 1000)
    )
    val page = factory.createDetailsPage(nodeDescriptor)
    TreeWalker(page).descendants().filterIsInstance<JEditorPane>().single().text.clearHtml().let { pageHtml ->
      Truth.assertThat(pageHtml).contains("Some plugins are not compatible with Configuration cache")
      Truth.assertThat(pageHtml).contains("You could save about 0.1s by")
      Truth.assertThat(pageHtml).contains("Some of the plugins applied are known to be not compatible with Configuration cache in versions used in this build.")
      Truth.assertThat(pageHtml).contains("2 plugins can be updated to the compatible version.")
      Truth.assertThat(pageHtml).doesNotContain("not known to have a compatible version yet, please contact plugin providers for details.")
      Truth.assertThat(pageHtml).contains("You can find details on each plugin on corresponding sub-pages.")
    }
  }

  @Test
  fun testConfigurationCacheIncompatiblePluginDetectedPageTwoIncompatible() {
    val factory = WarningsViewDetailPagesFactory(mockModel, mockHandlers)
    val incompatiblePluginA = GradlePluginsData.PluginInfo(
      name = "Incompatible Plugin A",
      pluginClasses = listOf(pluginA.idName),
      pluginArtifact = GradlePluginsData.DependencyCoordinates("my.org", "pluginA-jar"),
    )
    val incompatiblePluginB = GradlePluginsData.PluginInfo(
      name = "Incompatible Plugin B",
      pluginClasses = listOf(pluginB.idName),
      pluginArtifact = GradlePluginsData.DependencyCoordinates("my.org", "pluginB-jar"),
    )
    val nodeDescriptor = ConfigurationCachingRootNodeDescriptor(
      IncompatiblePluginsDetected(listOf(
        IncompatiblePluginWarning(pluginA, GradleVersion.parse("0.1.0"), incompatiblePluginA),
        IncompatiblePluginWarning(pluginB, GradleVersion.parse("1.1.0"), incompatiblePluginB)
      ), emptyList()),
      TimeWithPercentage(100, 1000)
    )
    val page = factory.createDetailsPage(nodeDescriptor)
    TreeWalker(page).descendants().filterIsInstance<JEditorPane>().single().text.clearHtml().let { pageHtml ->
      Truth.assertThat(pageHtml).contains("Some plugins are not compatible with Configuration cache")
      Truth.assertThat(pageHtml).contains("You could save about 0.1s by")
      Truth.assertThat(pageHtml).contains("Some of the plugins applied are known to be not compatible with Configuration cache in versions used in this build.")
      Truth.assertThat(pageHtml).contains("2 plugins are not known to have a compatible version yet,")
      Truth.assertThat(pageHtml).doesNotContain("can be updated to the compatible version.")
      Truth.assertThat(pageHtml).contains("You can find details on each plugin on corresponding sub-pages.")
    }
  }

  @Test
  fun testConfigurationCacheIncompatiblePluginDetectedPageOneIncompatibleOneUpdate() {
    val factory = WarningsViewDetailPagesFactory(mockModel, mockHandlers)
    val compatiblePluginA = GradlePluginsData.PluginInfo(
      name = "Compatible Plugin",
      pluginClasses = listOf(pluginA.idName),
      pluginArtifact = GradlePluginsData.DependencyCoordinates("my.org", "pluginA-jar"),
      configurationCachingCompatibleFrom = GradleVersion.parse("0.2.0")
    )
    val incompatiblePluginB = GradlePluginsData.PluginInfo(
      name = "Incompatible Plugin",
      pluginClasses = listOf(pluginB.idName),
      pluginArtifact = GradlePluginsData.DependencyCoordinates("my.org", "pluginB-jar"),
    )
    val nodeDescriptor = ConfigurationCachingRootNodeDescriptor(
      IncompatiblePluginsDetected(listOf(
        IncompatiblePluginWarning(pluginA, GradleVersion.parse("0.1.0"), compatiblePluginA)
      ), listOf(
        IncompatiblePluginWarning(pluginB, GradleVersion.parse("1.1.0"), incompatiblePluginB)
      )),
      TimeWithPercentage(100, 1000)
    )
    val page = factory.createDetailsPage(nodeDescriptor)
    TreeWalker(page).descendants().filterIsInstance<JEditorPane>().single().text.clearHtml().let { pageHtml ->
      Truth.assertThat(pageHtml).contains("Some plugins are not compatible with Configuration cache")
      Truth.assertThat(pageHtml).contains("You could save about 0.1s by")
      Truth.assertThat(pageHtml).contains("Some of the plugins applied are known to be not compatible with Configuration cache in versions used in this build.")
      Truth.assertThat(pageHtml).contains("1 plugin is not known to have a compatible version yet,")
      Truth.assertThat(pageHtml).contains("1 plugin can be updated to the compatible version.")
      Truth.assertThat(pageHtml).contains("You can find details on each plugin on corresponding sub-pages.")
    }
  }

  @Test
  fun testConfigurationCacheNoIncompatiblePluginsPage() {
    val factory = WarningsViewDetailPagesFactory(mockModel, mockHandlers)
    val nodeDescriptor = ConfigurationCachingRootNodeDescriptor(
      NoIncompatiblePlugins(listOf(pluginA)),
      TimeWithPercentage(100, 1000)
    )
    val page = factory.createDetailsPage(nodeDescriptor)
    TreeWalker(page).descendants().filterIsInstance<JEditorPane>().let { htmlPanes ->
      Truth.assertThat(htmlPanes).hasSize(3)
      htmlPanes[0].text.clearHtml().let { pageHtml ->
        Truth.assertThat(pageHtml).contains("Try to turn Configuration cache on")
        Truth.assertThat(pageHtml).contains("You could save about 0.1s by")
        Truth.assertThat(pageHtml).contains("The known plugins applied in this build are compatible with Configuration cache.")
      }
      htmlPanes[1].text.clearHtml().let { pageHtml ->
        Truth.assertThat(pageHtml).contains("Note: There could be unknown plugins that aren't compatible and are discovered after\n" +
                                            "you build with Configuration cache turned on.")
      }
      htmlPanes[2].text.clearHtml().let { pageHtml ->
        Truth.assertThat(pageHtml).contains("<b>List of applied plugins we were not able to recognise:</b>")
        Truth.assertThat(pageHtml).contains("my.org.gradle.PluginA")
      }
    }
    TreeWalker(page).descendants().filterIsInstance<JButton>().single().let { button ->
      Truth.assertThat(button.text).isEqualTo("Try Configuration cache in a build")
    }
  }

  @Test
  fun testConfigurationCachePluginRequireUpdatePage() {
    val factory = WarningsViewDetailPagesFactory(mockModel, mockHandlers)
    val compatiblePluginA = GradlePluginsData.PluginInfo(
      name = "Compatible Plugin",
      pluginClasses = listOf(pluginA.idName),
      pluginArtifact = GradlePluginsData.DependencyCoordinates("my.org", "pluginA-jar"),
      configurationCachingCompatibleFrom = GradleVersion.parse("0.2.0")
    )
    val nodeDescriptor = ConfigurationCachingWarningNodeDescriptor(
      IncompatiblePluginWarning(pluginA, GradleVersion.parse("0.1.0"), compatiblePluginA),
      TimeWithPercentage(100, 1000)
    )
    val page = factory.createDetailsPage(nodeDescriptor)
    TreeWalker(page).descendants().filterIsInstance<JEditorPane>().single().text.clearHtml().let { pageHtml ->
      Truth.assertThat(pageHtml).contains("<b>my.org.gradle.PluginA: update required</b>")
      Truth.assertThat(pageHtml).contains("You could save about 0.1s by")
      Truth.assertThat(pageHtml).contains("Update this plugin to 0.2.0 or higher to make Configuration cache available.")
      Truth.assertThat(pageHtml).contains("Plugin version: 0.1.0")
      Truth.assertThat(pageHtml).contains("Plugin dependency: my.org:pluginA-jar")
    }
    TreeWalker(page).descendants().filterIsInstance<JButton>().single().let { button ->
      Truth.assertThat(button.text).isEqualTo("Update plugin")
    }
  }

  @Test
  fun testConfigurationCachePluginNotCompatiblePage() {
    val factory = WarningsViewDetailPagesFactory(mockModel, mockHandlers)
    val incompatiblePluginA = GradlePluginsData.PluginInfo(
      name = "Incompatible Plugin A",
      pluginClasses = listOf(pluginA.idName),
      pluginArtifact = GradlePluginsData.DependencyCoordinates("my.org", "pluginA-jar"),
    )
    val nodeDescriptor = ConfigurationCachingWarningNodeDescriptor(
      IncompatiblePluginWarning(pluginA, GradleVersion.parse("0.1.0"), incompatiblePluginA),
      TimeWithPercentage(100, 1000)
    )
    val page = factory.createDetailsPage(nodeDescriptor)
    Truth.assertThat(TreeWalker(page).descendants().filterIsInstance<JButton>()).isEmpty()
  }

  private fun String.clearHtml(): String = UIUtil.getHtmlBody(this)
    .trimIndent()
    .replace("\n","")
    .replace("<br>","\n")
    .replace("<p>","\n")
    .replace("</p>","\n")
}