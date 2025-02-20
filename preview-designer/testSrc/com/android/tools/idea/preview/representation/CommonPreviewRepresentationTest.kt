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
package com.android.tools.idea.preview.representation

import com.android.testutils.delayUntilCondition
import com.android.tools.compile.fast.CompilationResult
import com.android.tools.compile.fast.isSuccess
import com.android.tools.configurations.Configuration
import com.android.tools.idea.common.model.NlModel
import com.android.tools.idea.concurrency.AndroidCoroutineScope
import com.android.tools.idea.concurrency.AndroidDispatchers.workerThread
import com.android.tools.idea.editors.build.ProjectStatus
import com.android.tools.idea.editors.fast.FastPreviewManager
import com.android.tools.idea.editors.fast.FastPreviewTrackerManager
import com.android.tools.idea.editors.fast.TestFastPreviewTrackerManager
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.preview.PreviewElementModelAdapter
import com.android.tools.idea.preview.PreviewElementProvider
import com.android.tools.idea.preview.PreviewRefreshManager
import com.android.tools.idea.preview.PsiTestPreviewElement
import com.android.tools.idea.preview.TestPreviewRefreshRequest
import com.android.tools.idea.preview.fast.FastPreviewSurface
import com.android.tools.idea.preview.flow.PreviewFlowManager
import com.android.tools.idea.preview.groups.PreviewGroupManager
import com.android.tools.idea.preview.modes.PreviewModeManager
import com.android.tools.idea.preview.mvvm.PREVIEW_VIEW_MODEL_STATUS
import com.android.tools.idea.preview.mvvm.PreviewViewModelStatus
import com.android.tools.idea.preview.requestRefreshSync
import com.android.tools.idea.preview.viewmodels.CommonPreviewViewModel
import com.android.tools.idea.preview.views.CommonNlDesignSurfacePreviewView
import com.android.tools.idea.preview.waitUntilRefreshStarts
import com.android.tools.idea.projectsystem.ProjectSystemService
import com.android.tools.idea.projectsystem.TestProjectSystem
import com.android.tools.idea.run.deployment.liveedit.setUpComposeInProjectFixture
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.addFileToProjectAndInvalidate
import com.android.tools.idea.testing.executeAndSave
import com.android.tools.idea.testing.insertText
import com.android.tools.idea.testing.moveCaret
import com.android.tools.rendering.RenderAsyncActionExecutor
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.runWriteActionAndWait
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.DumbModeTestUtils.waitForSmartMode
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.replaceService
import com.intellij.testFramework.runInEdtAndWait
import java.util.concurrent.CountDownLatch
import kotlin.test.assertFails
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.mock

private lateinit var previewView: CommonNlDesignSurfacePreviewView
private lateinit var previewViewModelMock: CommonPreviewViewModel

private class TestPreviewElementProvider(private val previewElement: PsiTestPreviewElement) :
  PreviewElementProvider<PsiTestPreviewElement> {
  override suspend fun previewElements(): Sequence<PsiTestPreviewElement> =
    sequenceOf(previewElement)
}

private class TestPreviewElementModelAdapter(private val previewElement: PsiTestPreviewElement) :
  PreviewElementModelAdapter<PsiTestPreviewElement, NlModel> {
  override fun calcAffinity(el1: PsiTestPreviewElement, el2: PsiTestPreviewElement?): Int = 0

  override fun toXml(previewElement: PsiTestPreviewElement): String = ""

  override fun applyToConfiguration(
    previewElement: PsiTestPreviewElement,
    configuration: Configuration,
  ) {}

  override fun modelToElement(model: NlModel): PsiTestPreviewElement? = previewElement

  override fun createDataContext(previewElement: PsiTestPreviewElement): DataContext =
    mock(DataContext::class.java)

  override fun toLogString(previewElement: PsiTestPreviewElement): String = ""

  override fun createLightVirtualFile(
    content: String,
    backedFile: VirtualFile,
    id: Long,
  ): LightVirtualFile = InMemoryLayoutVirtualFile("test.xml", content) { backedFile }
}

class CommonPreviewRepresentationTest {
  @get:Rule val projectRule = AndroidProjectRule.inMemory().withKotlin()
  private lateinit var myScope: CoroutineScope
  private lateinit var refreshManager: PreviewRefreshManager
  private lateinit var psiFile: PsiFile

  private val fixture
    get() = projectRule.fixture

  private val project
    get() = fixture.project

  @Before
  fun setup() {
    setUpComposeInProjectFixture(projectRule)
    runInEdtAndWait { TestProjectSystem(project).useInTests() }
    previewViewModelMock = mock(CommonPreviewViewModel::class.java)
    myScope = AndroidCoroutineScope(fixture.testRootDisposable)
    // use the "real" refresh manager and not a "for test" instance to actually test how the common
    // representation uses it
    refreshManager =
      PreviewRefreshManager.getInstance(RenderAsyncActionExecutor.RenderingTopic.NOT_SPECIFIED)

    psiFile = runWriteActionAndWait {
      fixture.addFileToProjectAndInvalidate(
        "Test.kt",
        // language=kotlin
        """
        fun MyFun() {
          println("Hello world!")
        }
      """
          .trimIndent(),
      )
    }
  }

  @After
  fun tearDown() {
    StudioFlags.PREVIEW_RENDER_QUALITY.clearOverride()
  }

  @Test
  fun testFullRefreshIsTriggeredOnSuccessfulBuild() =
    runBlocking(workerThread) {
      // Turn off flag to make sure quality refreshes won't affect the asserts in this test
      StudioFlags.PREVIEW_RENDER_QUALITY.override(false)
      val previewRepresentation = createPreviewRepresentation()
      previewRepresentation.compileAndWaitForRefresh()

      // block the refresh manager with a high priority refresh that won't finish
      val blockingRefresh = blockRefreshManager()

      // building the project again should invalidate the preview representation
      assertFalse(previewRepresentation.isInvalidatedForTest())
      ProjectSystemService.getInstance(project).projectSystem.getBuildManager().compileProject()
      delayUntilCondition(delayPerIterationMs = 1000, 20.seconds) {
        previewRepresentation.isInvalidatedForTest()
      }
      assertTrue(previewRepresentation.isInvalidatedForTest())

      // unblock the refresh manager
      TestPreviewRefreshRequest.expectedLogPrintCount.await()
      TestPreviewRefreshRequest.expectedLogPrintCount = CountDownLatch(1)
      blockingRefresh.waitUntilRefreshStarts()
      blockingRefresh.runningRefreshJob!!.cancel()
      TestPreviewRefreshRequest.expectedLogPrintCount.await()
      assertEquals(
        """
      start testRequest
      user-cancel testRequest
    """
          .trimIndent(),
        TestPreviewRefreshRequest.log.toString().trimIndent(),
      )

      // As a consequence of the build a refresh should happen in the preview representation now
      // that the refresh manager was unblocked
      delayUntilCondition(delayPerIterationMs = 1000, 10.seconds) {
        !previewRepresentation.isInvalidatedForTest()
      }
      assertFalse(previewRepresentation.isInvalidatedForTest())
      previewRepresentation.onDeactivate()
    }

  @Test
  fun testFastPreviewIsRequested() = runBlocking {
    val requestCompleted = CompletableDeferred<Unit>()
    val testTracker =
      TestFastPreviewTrackerManager(showTimes = false) { requestCompleted.complete(Unit) }
    val fastPreviewManager = FastPreviewManager.getInstance(project)
    assertTrue("FastPreviewManager must be enabled", fastPreviewManager.isEnabled)

    project.replaceService(
      FastPreviewTrackerManager::class.java,
      testTracker,
      fixture.testRootDisposable,
    )

    val previewRepresentation = createPreviewRepresentation()
    previewRepresentation.compileAndWaitForRefresh()

    val compileDeferred = CompletableDeferred<CompilationResult>()
    val fastPreviewManagerListener =
      object : FastPreviewManager.Companion.FastPreviewManagerListener {
        override fun onCompilationStarted(files: Collection<PsiFile>) {}

        override fun onCompilationComplete(result: CompilationResult, files: Collection<PsiFile>) {
          // We expect a successful compilation, but some cancelled results can be received here
          // if for some reason a compilation is started while another one was already happening
          if (result.isSuccess) compileDeferred.complete(result)
        }
      }
    fastPreviewManager.addListener(fixture.testRootDisposable, fastPreviewManagerListener)
    runWriteActionAndWait {
      projectRule.fixture.openFileInEditor(psiFile.virtualFile)
      projectRule.fixture.moveCaret("println(\"Hello world!\")|")
      projectRule.fixture.editor.executeAndSave {
        insertText("\nprintln(\"added during test execution\")")
      }
      PsiDocumentManager.getInstance(projectRule.project).commitAllDocuments()
      FileDocumentManager.getInstance().saveAllDocuments()
    }

    val result = compileDeferred.await()
    assertTrue(result.isSuccess)

    withTimeout(10.seconds) {
      // Wait for the tracking request to be submitted
      requestCompleted.await()
    }

    assertEquals("compilationSucceeded (compiledFiles=1)", testTracker.logOutput())
    previewRepresentation.onDeactivate()
  }

  @Test
  fun testDataKeysShouldBeRegistered() {
    runBlocking(workerThread) {
      val preview = createPreviewRepresentation()
      val surface = preview.previewView.mainSurface

      assertTrue(surface.getData(PreviewModeManager.KEY.name) is PreviewModeManager)
      assertThat(surface.getData(PREVIEW_VIEW_MODEL_STATUS.name) is PreviewViewModelStatus)
      assertThat(surface.getData(PreviewGroupManager.KEY.name) is PreviewGroupManager)
      assertThat(surface.getData(PreviewFlowManager.KEY.name) is PreviewFlowManager<*>)
      assertTrue(surface.getData(FastPreviewSurface.KEY.name) is FastPreviewSurface)

      preview.onDeactivate()
    }
  }

  @Test
  fun testReactivationWithoutChangesDontFullRefresh(): Unit =
    runBlocking(workerThread) {
      // Turn off flag to make sure quality refreshes won't affect the asserts in this test
      StudioFlags.PREVIEW_RENDER_QUALITY.override(false)
      val previewRepresentation = createPreviewRepresentation()
      previewRepresentation.compileAndWaitForRefresh()

      assertFalse(previewRepresentation.isInvalidatedForTest())
      previewRepresentation.onDeactivate()

      val blockingRefresh = blockRefreshManager()

      // reactivating the representation shouldn't enqueue a new refresh
      previewRepresentation.onActivate()
      assertFalse(previewRepresentation.isInvalidatedForTest())
      assertFails {
        delayUntilCondition(delayPerIterationMs = 1000, 5.seconds) {
          refreshManager.getTotalRequestsInQueueForTest() == 1
        }
      }
      assertFalse(previewRepresentation.isInvalidatedForTest())
      blockingRefresh.runningRefreshJob!!.cancel()
    }

  @Test
  fun testReactivationWithoutChangesDoesQualityRefresh(): Unit =
    runBlocking(workerThread) {
      val previewRepresentation = createPreviewRepresentation()
      previewRepresentation.compileAndWaitForRefresh()

      assertFalse(previewRepresentation.isInvalidatedForTest())
      var blockingRefresh = blockRefreshManager()
      previewRepresentation.onDeactivate()
      // Quality refresh on deactivation to decrease qualities
      delayUntilCondition(delayPerIterationMs = 1000, 5.seconds) {
        refreshManager.getTotalRequestsInQueueForTest() == 1
      }
      assertFalse(previewRepresentation.isInvalidatedForTest())
      // unblock and wait for the quality refresh to be taken out of the queue
      blockingRefresh.runningRefreshJob!!.cancel()
      delayUntilCondition(delayPerIterationMs = 1000, 5.seconds) {
        refreshManager.getTotalRequestsInQueueForTest() == 0
      }

      blockingRefresh = blockRefreshManager()
      previewRepresentation.onActivate()
      // Another quality refresh on reactivation
      assertFalse(previewRepresentation.isInvalidatedForTest())
      delayUntilCondition(delayPerIterationMs = 1000, 5.seconds) {
        refreshManager.getTotalRequestsInQueueForTest() == 1
      }
      assertFalse(previewRepresentation.isInvalidatedForTest())
      blockingRefresh.runningRefreshJob!!.cancel()
    }

  private suspend fun blockRefreshManager(): TestPreviewRefreshRequest {
    // block the refresh manager with a high priority refresh that won't finish
    TestPreviewRefreshRequest.log = StringBuilder()
    TestPreviewRefreshRequest.expectedLogPrintCount = CountDownLatch(1)
    val blockingRefresh =
      TestPreviewRefreshRequest(
        myScope,
        clientId = "testClient",
        priority = 100,
        name = "testRequest",
        doInsideRefreshJob = {
          while (true) {
            delay(500)
          }
        },
      )
    refreshManager.requestRefreshSync(blockingRefresh)
    TestPreviewRefreshRequest.expectedLogPrintCount.await()
    assertEquals(0, refreshManager.getTotalRequestsInQueueForTest())
    return blockingRefresh
  }

  private fun createPreviewRepresentation(): CommonPreviewRepresentation<PsiTestPreviewElement> {
    val previewElement = PsiTestPreviewElement()
    val previewElementProvider = TestPreviewElementProvider(previewElement)
    val previewElementModelAdapter = TestPreviewElementModelAdapter(previewElement)
    val previewRepresentation =
      CommonPreviewRepresentation(
        adapterViewFqcn = "TestAdapterViewFqcn",
        psiFile,
        { previewElementProvider },
        previewElementModelAdapter,
        viewConstructor = { project, surfaceBuilder, parentDisposable ->
          CommonNlDesignSurfacePreviewView(project, surfaceBuilder, parentDisposable).also {
            previewView = it
          }
        },
        viewModelConstructor = { _, _, _, _, _, _ -> previewViewModelMock },
        configureDesignSurface = {},
        renderingTopic = RenderAsyncActionExecutor.RenderingTopic.NOT_SPECIFIED,
      )
    Disposer.register(fixture.testRootDisposable, previewRepresentation)
    return previewRepresentation
  }

  private suspend fun CommonPreviewRepresentation<PsiTestPreviewElement>
    .compileAndWaitForRefresh() {
    // wait for smart mode and status to be needs build
    waitForSmartMode(fixture.project)
    delayUntilCondition(delayPerIterationMs = 1000, 10.seconds) {
      getProjectBuildStatusForTest() == ProjectStatus.NeedsBuild
    }

    // Activate and wait for build listener setup to finish
    assertFalse(hasBuildListenerSetupFinishedForTest())
    onActivate()
    delayUntilCondition(delayPerIterationMs = 1000, 10.seconds) {
      hasBuildListenerSetupFinishedForTest()
    }
    delayUntilCondition(delayPerIterationMs = 1000, 10.seconds) {
      hasFlowInitializationFinishedForTest()
    }
    assertTrue(isInvalidatedForTest())

    // Build the project and wait for a refresh to happen, setting the 'invalidated' to false
    ProjectSystemService.getInstance(project).projectSystem.getBuildManager().compileProject()
    delayUntilCondition(delayPerIterationMs = 1000, 20.seconds) {
      !isInvalidatedForTest() &&
        refreshManager.getTotalRequestsInQueueForTest() == 0 &&
        refreshManager.refreshingTypeFlow.value == null
    }
    assertFalse(isInvalidatedForTest())
  }
}
