/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.tools.idea.nav.safeargs.kotlin.k2

import com.android.tools.idea.nav.safeargs.SafeArgsMode
import com.android.tools.idea.nav.safeargs.module.NavInfoFetcher
import org.jetbrains.kotlin.analysis.api.resolve.extensions.KtResolveExtension
import org.jetbrains.kotlin.analysis.api.resolve.extensions.KtResolveExtensionProvider
import org.jetbrains.kotlin.analysis.project.structure.KtModule
import org.jetbrains.kotlin.analysis.project.structure.KtSourceModule
import org.jetbrains.kotlin.idea.base.projectStructure.ideaModule

class SafeArgsResolveExtensionProvider : KtResolveExtensionProvider() {
  override fun provideExtensionsFor(module: KtModule): List<KtResolveExtension> =
    when (module) {
      is KtSourceModule -> {
        val ideaModule = module.ideaModule
        ChangeListenerProjectService.ensureListening(ideaModule.project)

        if (NavInfoFetcher.isSafeArgsModule(module.ideaModule, SafeArgsMode.KOTLIN)) {
          listOf(SafeArgsResolveExtensionModuleService.getInstance(module.ideaModule))
        } else {
          emptyList()
        }
      }
      else -> emptyList()
    }
}
