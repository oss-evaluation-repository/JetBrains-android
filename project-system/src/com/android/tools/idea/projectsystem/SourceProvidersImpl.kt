/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.tools.idea.projectsystem

import kotlinx.collections.immutable.toImmutableMap

class SourceProvidersImpl(
  override val mainIdeaSourceProvider: NamedIdeaSourceProvider,
  override val currentSourceProviders: List<NamedIdeaSourceProvider>,
  override val currentHostTestSourceProviders: Map<TestComponentType, List<NamedIdeaSourceProvider>>,
  override val currentDeviceTestSourceProviders: Map<TestComponentType, List<NamedIdeaSourceProvider>>,
  override val currentTestFixturesSourceProviders: List<NamedIdeaSourceProvider>,
  override val currentAndSomeFrequentlyUsedInactiveSourceProviders: List<NamedIdeaSourceProvider>,

  @Suppress("OverridingDeprecatedMember")
  override val mainAndFlavorSourceProviders: List<NamedIdeaSourceProvider>,
  override val generatedSources: IdeaSourceProvider,
  override val generatedHostTestSources: Map<TestComponentType, IdeaSourceProvider>,
  override val generatedDeviceTestSources: Map<TestComponentType, IdeaSourceProvider>,
  override val generatedTestFixturesSources: IdeaSourceProvider
) : SourceProviders {
  override val sources: IdeaSourceProvider =
    createMergedSourceProvider(ScopeType.MAIN, currentSourceProviders)
  override val hostTestSources: Map<TestComponentType, IdeaSourceProvider> =
    mutableMapOf<TestComponentType, IdeaSourceProvider>().apply {
       currentHostTestSourceProviders.forEach {
         put(it.key, createMergedSourceProvider(it.key.scopeTypeByName(), it.value))
       }
     }.toImmutableMap()
  override val deviceTestSources: Map<TestComponentType, IdeaSourceProvider> =
    mutableMapOf<TestComponentType, IdeaSourceProvider>().apply {
      currentDeviceTestSourceProviders.forEach {
        put(it.key, createMergedSourceProvider(it.key.scopeTypeByName(), it.value))
      }
    }.toImmutableMap()
  override val testFixturesSources: IdeaSourceProvider =
    createMergedSourceProvider(ScopeType.TEST_FIXTURES, currentTestFixturesSourceProviders)

  /**
   * Secondary constructor temporarily needed for backward compatibility with the consumer call in {@link BlazeProjectSystem}
   * This constructor can be removed once ASwB is fully migrated to repo.
   * TODO(b/325413671)
    */
  constructor(
      mainIdeaSourceProvider: NamedIdeaSourceProvider,
      currentSourceProviders: List<NamedIdeaSourceProvider>,
      currentUnitTestSourceProviders: List<NamedIdeaSourceProvider>,
      currentAndroidTestSourceProviders: List<NamedIdeaSourceProvider>,
      currentTestFixturesSourceProviders: List<NamedIdeaSourceProvider>,
      currentAndSomeFrequentlyUsedInactiveSourceProviders: List<NamedIdeaSourceProvider>,
      mainAndFlavorSourceProviders: List<NamedIdeaSourceProvider>,
      generatedSources: IdeaSourceProvider,
      generatedUnitTestSources: IdeaSourceProvider,
      generatedAndroidTestSources: IdeaSourceProvider,
      generatedTestFixturesSources: IdeaSourceProvider
    ): this(
      mainIdeaSourceProvider,
      currentSourceProviders,
      mapOf(CommonTestType.UNIT_TEST to currentUnitTestSourceProviders),
      mapOf(CommonTestType.ANDROID_TEST to currentAndroidTestSourceProviders),
      currentTestFixturesSourceProviders,
      currentAndSomeFrequentlyUsedInactiveSourceProviders,
      mainAndFlavorSourceProviders,
      generatedSources,
      mapOf(CommonTestType.UNIT_TEST to generatedUnitTestSources),
      mapOf(CommonTestType.ANDROID_TEST to generatedAndroidTestSources),
      generatedTestFixturesSources
    )
}
