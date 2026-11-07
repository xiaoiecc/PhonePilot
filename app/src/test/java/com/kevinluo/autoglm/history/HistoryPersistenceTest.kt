package com.kevinluo.autoglm.history

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class HistoryPersistenceTest {
    @Test
    fun `text only completion and early failure are persisted`() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("agent-history").toFile()
        val context = mockk<Context>()
        every { context.filesDir } returns directory
        val manager = HistoryManager::class.java.getDeclaredConstructor(Context::class.java).apply {
            isAccessible = true
        }.newInstance(context)

        manager.startTask("answer")
        manager.recordMessage(HistoryMessageRole.USER, "answer")
        manager.recordMessage(HistoryMessageRole.AGENT, "done")
        manager.completeTask(true, "done")
        assertTrue(File(directory, "task_history").listFiles()?.isNotEmpty() == true)

        manager.startTask("request fails")
        manager.recordMessage(HistoryMessageRole.USER, "request fails")
        manager.completeTask(false, "network error")
        assertEquals(2, manager.historyList.value.size)
    }
}
