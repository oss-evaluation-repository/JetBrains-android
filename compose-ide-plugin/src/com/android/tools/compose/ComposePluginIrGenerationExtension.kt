/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.tools.compose

import androidx.compose.compiler.plugins.kotlin.ComposeIrGenerationExtension
import androidx.compose.compiler.plugins.kotlin.FeatureFlag
import androidx.compose.compiler.plugins.kotlin.FeatureFlag.IntrinsicRemember
import androidx.compose.compiler.plugins.kotlin.FeatureFlags
import androidx.compose.compiler.plugins.kotlin.IncompatibleComposeRuntimeVersionException
import com.android.tools.idea.run.deployment.liveedit.CompileScope
import com.intellij.openapi.progress.ProcessCanceledException
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.idea.base.plugin.KotlinPluginModeProvider
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

private val liveEditPackageName = "${CompileScope::class.java.packageName}."

@Suppress("INVISIBLE_REFERENCE", "EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(org.jetbrains.kotlin.extensions.internal.InternalNonStableExtensionPoints::class)
class ComposePluginIrGenerationExtension : IrGenerationExtension {
  override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    try {
      ComposeIrGenerationExtension(
          reportsDestination = null,
          metricsDestination = null,
          generateFunctionKeyMetaClasses = true,
          useK2 = KotlinPluginModeProvider.isK2Mode(),
          featureFlags = FeatureFlags().apply {
            setFeature(IntrinsicRemember, false)
          }
        )
        .generate(moduleFragment, pluginContext)
    } catch (e: ProcessCanceledException) {
      // From ProcessCanceledException javadoc: "Usually, this exception should not be caught,
      // swallowed, logged, or handled in any way.
      // Instead, it should be rethrown so that the infrastructure can handle it correctly."
      throw e
    } catch (versionError: IncompatibleComposeRuntimeVersionException) {
      // We only rethrow version incompatibility when we are trying to CodeGen for Live Edit.
      for (s in versionError.stackTrace) {
        if (s.className.startsWith(liveEditPackageName)) {
          throw versionError
        }
      }
      versionError.printStackTrace()
    } catch (t: Throwable) {
      t.printStackTrace()
    }
  }
}
