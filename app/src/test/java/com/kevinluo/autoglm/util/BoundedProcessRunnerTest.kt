package com.kevinluo.autoglm.util

import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BoundedProcessRunnerTest {
    private fun command(mode: String): List<String> {
        val binary = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val java = File(System.getProperty("java.home"), "bin/$binary").absolutePath
        val fixtureDirectory = File(ProcessRunnerFixture::class.java.protectionDomain.codeSource.location.toURI()).absolutePath
        return listOf(java, "-Xms16m", "-Xmx64m", "-XX:+UseSerialGC", "-cp", fixtureDirectory, ProcessRunnerFixture::class.java.name, mode)
    }

    @Test
    fun `stdout stderr and exit marker remain compatible`() {
        val result = BoundedProcessRunner().execute(command("normal"), 5000)
        assertEquals("hello\n", result.stdout.replace("\r\n", "\n"))
        assertEquals("warning\n", result.stderr.replace("\r\n", "\n"))
        assertEquals(7, result.exitCode)
        assertTrue(result.formatted().contains("[stderr]\nwarning"))
        assertTrue(result.formatted().endsWith("[exit code: 7]"))
    }

    @Test
    fun `both full pipes are drained concurrently without deadlock`() {
        val result = BoundedProcessRunner().execute(command("flood"), 5000)
        assertEquals(0, result.exitCode)
        assertEquals(200000, result.stdout.length)
        assertEquals(200000, result.stderr.length)
        assertTrue(result.stdout.all { it == 'o' })
        assertTrue(result.stderr.all { it == 'e' })
    }

    @Test
    fun `a blocked command is destroyed at its deadline`() {
        val child = AtomicReference<Process>()
        val runner = BoundedProcessRunner(startProcess = { ProcessBuilder(it).start().also(child::set) })
        val started = System.nanoTime()
        val result = runner.execute(command("sleep"), 200)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(124, result.exitCode)
        assertTrue(result.stderr.contains("timed out"))
        assertTrue(elapsedMs < 3000, "Timeout cleanup took ${elapsedMs}ms")
        assertFalse(child.get().isAlive)
    }

    @Test
    fun `combined output budget stops both producers and bounds the returned data`() {
        val child = AtomicReference<Process>()
        val runner = BoundedProcessRunner(startProcess = { ProcessBuilder(it).start().also(child::set) })
        val result = runner.execute(command("limit"), 5000, 10000)
        assertEquals(125, result.exitCode)
        assertTrue(result.stderr.contains("output limit exceeded"))
        assertTrue(result.stdout.length + result.stderr.substringBefore("Error:").length <= 10001)
        assertFalse(child.get().isAlive)
    }
}
