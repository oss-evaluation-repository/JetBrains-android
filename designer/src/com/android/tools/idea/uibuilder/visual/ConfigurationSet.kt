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
package com.android.tools.idea.uibuilder.visual

interface ConfigurationSet {
  /** The unique id of this models provider. */
  val id: String

  /**
   * The name of this models provider. The name can be duplicated, and it shows on the dropdown menu
   * of configuration set action.
   */
  val name: String
  val visible: Boolean
    get() = true

  fun createModelsProvider(listener: ConfigurationSetListener): VisualizationModelsProvider

  object PixelDevices : ConfigurationSet {
    override val id = "pixelDevices"
    override val name = "Pixel Devices"

    override fun createModelsProvider(listener: ConfigurationSetListener) =
      PixelDeviceModelsProvider

    override val visible = false
  }

  object WearDevices : ConfigurationSet {
    override val id = "wearOsDevices"
    override val name = "Wear OS Devices"

    override fun createModelsProvider(listener: ConfigurationSetListener) = WearDeviceModelsProvider
  }

  object ProjectLocal : ConfigurationSet {
    override val id = "projectLocales"
    override val name = "Project Locales"

    override fun createModelsProvider(listener: ConfigurationSetListener) = LocaleModelsProvider

    override val visible = true
  }

  object ColorBlindMode : ConfigurationSet {
    override val id = "colorBlind"
    override val name = "Color Blind"

    override fun createModelsProvider(listener: ConfigurationSetListener) =
      ColorBlindModeModelsProvider
  }

  object LargeFont : ConfigurationSet {
    override val id = "fontSizes"
    override val name = "Font Sizes"

    override fun createModelsProvider(listener: ConfigurationSetListener) = LargeFontModelsProvider
  }

  /** This is also known as "Reference Device". */
  object WindowSizeDevices : ConfigurationSet {
    override val id = "windowSizeDevices"
    override val name = "Reference Devices"

    override fun createModelsProvider(listener: ConfigurationSetListener) = WindowSizeModelsProvider

    override val visible = true
  }
}

/**
 * The custom category which is created by user. The user-made custom category is removable, which
 * means user can delete the custom category if they choose.
 */
class UserDefinedCustom(
  override val id: String,
  val customConfigurationSet: CustomConfigurationSet,
) : ConfigurationSet {
  override val name: String = customConfigurationSet.title

  override fun createModelsProvider(listener: ConfigurationSetListener) =
    CustomModelsProvider(id, customConfigurationSet, listener)

  fun setCustomName(customName: String) {
    this.customConfigurationSet.title = customName
    VisualizationUtil.setCustomConfigurationSet(id, this.customConfigurationSet)
  }
}

object ConfigurationSetProvider {
  @JvmField val defaultSet = ConfigurationSet.WindowSizeDevices

  @JvmStatic
  fun getConfigurationSets(): List<ConfigurationSet> = getGroupedConfigurationSets().flatten()

  @JvmStatic
  fun getGroupedConfigurationSets(): List<List<ConfigurationSet>> {
    val predefinedGroup1 =
      listOf(
        ConfigurationSet.WindowSizeDevices,
        ConfigurationSet.WearDevices,
        ConfigurationSet.ProjectLocal,
      )
    val customGroup = VisualizationUtil.getUserMadeConfigurationSets()
    val predefinedGroup2 = listOf(ConfigurationSet.ColorBlindMode, ConfigurationSet.LargeFont)

    return listOf(predefinedGroup1, customGroup, predefinedGroup2)
  }

  @JvmStatic
  fun getConfigurationById(id: String): ConfigurationSet? =
    getConfigurationSets().firstOrNull { it.id == id }
}

interface ConfigurationSetListener {
  /**
   * Callback when selected [ConfigurationSet] is changed. For example, the selected
   * [ConfigurationSet] is changed from [ConfigurationSet.PixelDevices] to
   * [ConfigurationSet.ProjectLocal].
   */
  fun onSelectedConfigurationSetChanged(newConfigurationSet: ConfigurationSet)

  /**
   * Callback when the current [ConfigurationSet] changes the provided
   * [com.android.tools.idea.common.model.NlModel]s. For example, when user add one more
   * configuration in a [UserDefinedCustom]. In such case this callback is triggered because the
   * [UserDefinedCustom] now provides one more [com.android.tools.idea.common.model.NlModel].
   */
  fun onCurrentConfigurationSetUpdated()
}
