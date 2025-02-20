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
package com.android.tools.idea.streaming.device

import com.android.tools.idea.flags.StudioFlags
import com.intellij.openapi.diagnostic.thisLogger
import java.util.function.Supplier

object B330395367Logger {
  private val LOG = thisLogger()

  fun log(lazyMessage: Supplier<String>) {
    if (StudioFlags.DEVICE_MIRRORING_B330395367_LOGGING.get()) {
      LOG.info(lazyMessage.get())
    }
  }
}