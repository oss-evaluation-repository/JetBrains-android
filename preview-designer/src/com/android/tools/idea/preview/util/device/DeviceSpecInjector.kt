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
package com.android.tools.idea.preview.util.device

import com.android.tools.idea.kotlin.tryEvaluateConstant
import com.android.tools.preview.config.PARAMETER_DEVICE
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.lang.injection.general.Injection
import com.intellij.lang.injection.general.LanguageInjectionContributor
import com.intellij.lang.injection.general.LanguageInjectionPerformer
import com.intellij.lang.injection.general.SimpleInjection
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parentOfType
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.base.plugin.suppressAndroidPlugin
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UNamedExpression
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.toUElement

/**
 * Denotes which [PsiElement] may be injected with the [DeviceSpecLanguage].
 *
 * For the @Preview device parameter, the first [KtStringTemplateExpression] in Kotlin, or
 * [PsiLiteralExpression] in Java, may be injected. [KtDeviceSpecInjectionPerformer] will handle
 * injecting all other [KtStringTemplateExpression] present in the parameter's value in Kotlin. In
 * Java, string concatenation in the device parameter is not supported as [JavaInjectionPerformer]
 * takes precedence over other [LanguageInjectionPerformer]s. [JavaInjectionPerformer] does not
 * handle string concatenation with references to other variables properly.
 *
 * To identify which @Preview is a valid preview annotation, subclasses must implement
 * [isPreviewAnnotation].
 */
abstract class DeviceSpecInjectionContributor : LanguageInjectionContributor {
  override fun getInjection(context: PsiElement): Injection? {
    val application = ApplicationManager.getApplication()
    if (!application.isUnitTestMode && application.isDispatchThread) {
      // We need this contributor to provide a DeviceSpecLanguage Injection when getInjection
      // is called from IntelliJ editor workflows, such as EditorMouseHoverPopupManager, or
      // BackgroundHighlightingUtil. The goal is to inject the device spec custom language support
      // on the String value that represents a device in Preview. These editor workflows
      // are not triggered from EDT. Instead, they can be triggered from the ActionUpdateThread or
      // a pooled thread.
      //
      // There is nothing preventing getInjection to also be called from EDT. In Android Studio, we
      // do that through ReformatUtil.reformatRearrangeAndSave(), which is called by
      // NewTemplateRenderer when creating a new project. At that point, we don't need the Injection
      // to be provided, because getInjection will be called again from an appropriate thread when
      // the editor is ready.
      //
      // Since this is not needed on EDT and considering that context.isInPreviewAnnotation() below
      // relies on a slow operation (getting the FQN of an annotation), we return a null Injection
      // when running from EDT.
      //
      // Note: unit tests using CodeInsightTestFixture trigger the editor operations mentioned above
      // from the UI thread, so we don't return early when running tests
      return null
    }

    if (
      context.containingFile.fileType != KotlinFileType.INSTANCE &&
        context.containingFile.fileType != JavaFileType.INSTANCE
    ) {
      return null
    }

    val expression = context.toUElement(UExpression::class.java) ?: return null
    val annotation = expression.getParentOfType(UAnnotation::class.java) ?: return null

    if (!isPreviewAnnotation(annotation)) {
      return null
    }

    val namedExpression = expression.getParentOfType(UNamedExpression::class.java) ?: return null
    if (
      !namedExpression.isForDeviceParameter() ||
        namedExpression.getFirstStringExpression() !== context
    ) {
      return null
    }
    return SimpleInjection(DeviceSpecLanguage, "", "", null)
  }

  /**
   * Returns if the [UAnnotation] is a preview annotation. Each subclass must implement this method
   * to identify their preview-specific annotation.
   */
  protected abstract fun isPreviewAnnotation(annotation: UAnnotation): Boolean
}

/**
 * Injects the [DeviceSpecLanguage] into the given [PsiElement] and any other
 * [PsiLanguageInjectionHost]s available within the same expression via [MultiHostRegistrar] to
 * support concatenated injected elements.
 *
 * Since the Language cannot be injected to constant references, those are resolved and then added
 * to the injection of the closest [PsiLanguageInjectionHost]. Preserving the full statement in the
 * final injected file.
 *
 * E.g:
 * ```
 * const val width = "width=1080px"
 *
 * @Preview(device = "spec:" + width + ",height=1920px")
 * ```
 *
 * Injects the [DeviceSpecLanguage] in the first and last expressions, while the value of the
 * constant (width=1080px) is applied as a prefix to the injected file of the last expression,
 * resulting internally in: `"width=1080px,height1920px"`.
 *
 * So, contents of the injected file within `"spec:"` is: `"spec:width1080px,height1920px"`.
 */
class KtDeviceSpecInjectionPerformer : LanguageInjectionPerformer {
  override fun isPrimary(): Boolean {
    return false
  }

  override fun performInjection(
    registrar: MultiHostRegistrar,
    injection: Injection,
    context: PsiElement,
  ): Boolean {
    if (suppressAndroidPlugin()) return false

    val containingExpression =
      context.parentOfType<KtValueArgument>()?.getArgumentExpression() ?: return false

    val internalExpressions =
      PsiTreeUtil.collectElements(containingExpression) {
        when {
          it is PsiLanguageInjectionHost && it.children.isNotEmpty() -> true
          it is KtNameReferenceExpression -> true
          else -> false
        }
      }
    val injectionSegments = internalExpressions.collectInjectionSegments()
    if (injectionSegments.isEmpty()) {
      return false
    }
    registrar.startInjecting(injection.injectedLanguage ?: DeviceSpecLanguage)
    injectionSegments.forEach { it.injectTo(registrar) }
    registrar.doneInjecting()
    return true
  }
}

/**
 * Contains the information used by [MultiHostRegistrar] when injecting a concatenated Language (in
 * segments).
 */
private data class InjectionSegment(
  val injectionHost: PsiLanguageInjectionHost,
  val precedingContent: String = "",
  val followingContent: String = "",
) {
  fun injectTo(registrar: MultiHostRegistrar) {
    registrar.addPlace(
      precedingContent,
      followingContent,
      injectionHost,
      TextRange.from(1, injectionHost.textLength - 1),
    )
  }
}

/**
 * Organizes the elements so that the values of elements referencing a constant are added as a
 * suffix or prefix of a [PsiLanguageInjectionHost] element, depending on their order.
 *
 * @see InjectionSegment
 */
private fun Array<PsiElement>.collectInjectionSegments(): List<InjectionSegment> {
  val collectedSegments = mutableListOf<InjectionSegment>()

  var prefixConstant = ""
  var suffixConstant = ""
  var lastHost: PsiLanguageInjectionHost? = null
  this.forEach { expression ->
    when (expression) {
      is PsiLanguageInjectionHost -> {
        lastHost?.let {
          collectedSegments.add(
            InjectionSegment(
              injectionHost = it,
              precedingContent = prefixConstant,
              followingContent = "",
            )
          )
          prefixConstant = ""
        }

        prefixConstant = suffixConstant
        suffixConstant = ""
        lastHost = expression
      }
      is KtSimpleNameExpression -> {
        suffixConstant += expression.tryEvaluateConstant() ?: ""
      }
    }
  }
  lastHost?.let {
    collectedSegments.add(
      InjectionSegment(
        injectionHost = it,
        precedingContent = prefixConstant,
        followingContent = suffixConstant,
      )
    )
  }
  return collectedSegments
}

private fun UNamedExpression.isForDeviceParameter(): Boolean = name == PARAMETER_DEVICE

/**
 * This function returns the [PsiElement] representing the first string expression in the
 * [UNamedExpression] descendants, if there is one. In the case of kotlin, that element is of type
 * [KtStringTemplateExpression]. In the case of java, that element is [PsiLiteralExpression].
 */
private fun UNamedExpression.getFirstStringExpression(): PsiElement? {
  val psiExpressionType =
    when (lang) {
      is KotlinLanguage -> KtStringTemplateExpression::class.java
      is JavaLanguage -> PsiLiteralExpression::class.java
      else -> throw IllegalStateException("Unsupported language $lang")
    }
  return PsiTreeUtil.findChildOfType(expression.sourcePsi, psiExpressionType, false)
}
