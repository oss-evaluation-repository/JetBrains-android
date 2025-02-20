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
package com.android.tools.idea.preview.animation

import javax.swing.JPanel

/** Card displayed in [AllTabPanel]. Each animation represented by one [Card]. */
interface Card {
  /** [Card] component what should be added to the layout. */
  val component: JPanel

  /** Height of the component. */
  fun getCurrentHeight(): Int

  val title: String

  /** The size of the [Card] after it expands. */
  var expandedSize: Int

  /** Set duration of the animation. */
  fun setDuration(durationMillis: Int)
}
