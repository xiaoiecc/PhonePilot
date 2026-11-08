package com.kevinluo.autoglm.computer.runtime

import com.kevinluo.autoglm.accessibility.PhoneAccessibilityService
import com.kevinluo.autoglm.accessibility.SemanticAction
import com.kevinluo.autoglm.accessibility.SemanticKind
import com.kevinluo.autoglm.accessibility.SemanticNode
import com.kevinluo.autoglm.accessibility.UiSnapshot
import com.kevinluo.autoglm.core.ExecutionMode
import com.kevinluo.autoglm.core.RunCoordinator
import com.kevinluo.autoglm.core.RunPhase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExecutionPlacementObservationTest {
    @Test fun `migration after capture cannot append a different display tree beside the old image`() = runTest {
        val service = mockk<PhoneAccessibilityService>(relaxed = true)
        val h = RunnerHarness(this, service = service)
        val coordinator = RunCoordinator(this, { h.runner }, switchPlacement = { _, _ -> })
        var switched = false
        coEvery { h.history.setCurrentScreenshot(any(), any(), any()) } coAnswers {
            if (!switched) {
                switched = true
                assertTrue(coordinator.requestExecutionMode(requireNotNull(coordinator.snapshot.value.runId), ExecutionMode.BACKGROUND))
            }
        }
        coEvery { service.observe() } coAnswers {
            assertEquals(ExecutionMode.BACKGROUND, coordinator.snapshot.value.executionMode)
            UiSnapshot("background-snapshot", "new background tree", listOf(SemanticNode("background-node", SemanticKind.BUTTON,
                label = "Background target only", actions = listOf(SemanticAction("click")))), 9)
        }
        h.respond(screenshotRequest(), finalResponse())
        assertTrue(coordinator.submit("task"))
        advanceUntilIdle()
        assertEquals(RunPhase.COMPLETED, coordinator.snapshot.value.phase)
        coVerify(exactly = 1) { h.screenshots.capture() }
        coVerify(exactly = 1) { service.observe() }
        coVerify { h.client.request(match { input ->
            val text = input.toString()
            input.count { it.jsonObject["call_id"]?.jsonPrimitive?.content == "shot" } == 1 &&
                text.contains("historical, not the current target") && text.contains("background-snapshot") &&
                text.contains("Background target only")
        }, "r1") }
        coVerify(exactly = 0) { h.device.tap(any(), any()) }
    }

    @Test fun `migration between turns retains completed outputs and refreshes unavailable tree with one new capture`() = runTest {
        val h = RunnerHarness(this)
        val coordinator = RunCoordinator(this, { h.runner }, switchPlacement = { _, _ -> })
        val old = h.frame.copy(base64Data = "old-display-image")
        val fresh = h.frame.copy(base64Data = "new-display-image")
        coEvery { h.screenshots.capture() } returnsMany listOf(old, fresh)
        var switched = false
        coEvery { h.history.recordStep(any(), any(), any(), any(), any(), any()) } coAnswers {
            if (!switched) {
                switched = true
                assertTrue(coordinator.requestExecutionMode(requireNotNull(coordinator.snapshot.value.runId), ExecutionMode.BACKGROUND))
            }
        }
        h.respond(screenshotRequest(), finalResponse())
        assertTrue(coordinator.submit("task"))
        advanceUntilIdle()
        assertEquals(RunPhase.COMPLETED, coordinator.snapshot.value.phase)
        coVerify(exactly = 2) { h.screenshots.capture() }
        coVerify { h.client.request(match { input ->
            val text = input.toString()
            input.count { it.jsonObject["call_id"]?.jsonPrimitive?.content == "shot" } == 1 &&
                text.contains("old-display-image") && text.contains("new-display-image") &&
                text.contains("historical, not the current target") &&
                text.indexOf("new-display-image") > text.indexOf("old-display-image")
        }, "r1") }
        coVerify(exactly = 0) { h.device.tap(any(), any()) }
    }
}
