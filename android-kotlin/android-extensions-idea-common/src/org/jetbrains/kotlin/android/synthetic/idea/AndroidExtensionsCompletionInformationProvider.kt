/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.android.synthetic.idea

import com.intellij.psi.xml.XmlAttributeValue
import org.jetbrains.kotlin.android.synthetic.res.AndroidSyntheticProperty
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.idea.completion.CompletionInformationProvider
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.resolve.source.PsiSourceElement

class AndroidExtensionsCompletionInformationProvider : CompletionInformationProvider {
    override fun getContainerAndReceiverInformation(descriptor: DeclarationDescriptor): String? {
        if (descriptor !is AndroidSyntheticProperty) {
            return null
        }

        val propertyDescriptor = (descriptor as? PropertyDescriptor) ?: return null
        val attributeValue = (propertyDescriptor.source as? PsiSourceElement)?.psi as? XmlAttributeValue ?: return null
        val extensionReceiverType = propertyDescriptor.original.extensionReceiverParameter?.type

        return buildString {
            append(" from ${attributeValue.containingFile.name}")
            extensionReceiverType?.let { append(" for " + DescriptorRenderer.SHORT_NAMES_IN_TYPES.renderType(it)) }
            append(" (Android Extensions)")
        }
    }
}