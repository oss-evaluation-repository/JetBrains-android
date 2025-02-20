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
package com.android.tools.idea.gradle.project.build.output;

import com.intellij.build.output.BuildOutputInstantReader;
import org.jetbrains.annotations.NotNull;

public class BuildOutputParserUtils {
  @NotNull public static final String MESSAGE_GROUP_INFO_SUFFIX = " info";
  @NotNull public static final String MESSAGE_GROUP_STATISTICS_SUFFIX = " statistics";
  @NotNull public static final String MESSAGE_GROUP_WARNING_SUFFIX = " warnings";
  @NotNull public static final String MESSAGE_GROUP_ERROR_SUFFIX = " errors";

  @NotNull public static final String BUILD_FAILED_WITH_EXCEPTION_LINE = "FAILURE: Build failed with an exception.";

  public static void consumeRestOfOutput(BuildOutputInstantReader reader) {
    while (true) {
      String nextLine = reader.readLine();
      if (nextLine == null || nextLine.startsWith("BUILD FAILED") || nextLine.startsWith("CONFIGURE FAILED")) break;
    }
  }
}
