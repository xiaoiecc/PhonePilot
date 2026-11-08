package com.kevinluo.autoglm.architecture

import com.kevinluo.autoglm.core.ConversationSnapshot
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Repository guards against accidental dependency drift, not an adversarial source-code sandbox. */
class ArchitectureContractTest {
    private val root: Path = repositoryRoot()
    private val appSources = root.resolve("app/src/main/java/com/kevinluo/autoglm")

    @Test
    fun `automation core cannot depend on Android application or network implementations`() {
        val sourceRoot = root.resolve("automation-core/src/main")
        val forbiddenImport = Regex(
            "(?m)^\\s*import\\s+(android\\.|androidx\\.|rikka\\.|moe\\.shizuku\\.|okhttp3\\.|retrofit2\\.|io\\.ktor\\.|java\\.net\\.|javax\\.net\\.|com\\.kevinluo\\.autoglm\\.(?!core(?:\\.|\\s|$)))",
        )
        val failures = sourceFiles(sourceRoot).mapNotNull { file ->
            forbiddenImport.find(codeOnly(readSource(file)))?.let {
                "${root.relativize(file)} imports an Android/app/network dependency"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))

        val build = readSource(root.resolve("automation-core/build.gradle.kts"))
        val androidPlugin = Regex("com\\.android\\.|libs\\.plugins\\.android")
        assertTrue(!androidPlugin.containsMatchIn(build), "automation-core must remain a Kotlin JVM module")
        val productionDependencies = Regex("(?m)^\\s*(implementation|api|compileOnly|runtimeOnly)\\s*\\(([^\\r\\n]*)\\)")
            .findAll(build).map { it.groupValues[2].trim() }.toList()
        assertEquals(listOf("libs.kotlinx.coroutines.core"), productionDependencies,
            "New core production dependencies require an explicit architecture decision")
    }

    @Test
    fun `removed execution engines cannot silently return under another source filename`() {
        val obsolete = setOf("ActionHandler", "ActionParser", "AgentContext", "WaitController", "AndroidUserInteraction", "AndroidFunctionToolExecutor")
        val declaration = Regex("\\b(?:class|object|interface|typealias)\\s+(${obsolete.joinToString("|")})\\b")
        val failures = sourceFiles(appSources).mapNotNull { file ->
            val code = codeOnly(readSource(file))
            val declared = declaration.find(code)?.groupValues?.get(1)
            val oldFilename = file.fileName.toString().substringBeforeLast('.') in obsolete
            when {
                declared != null -> "${root.relativize(file)} reintroduces executable legacy type $declared"
                oldFilename -> "${root.relativize(file)} retains an obsolete execution entry point"
                else -> null
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `UI can submit task commands but cannot reach device or process execution directly`() {
        val uiFiles = sourceFiles(appSources).filter { file ->
            val relative = appSources.relativize(file).toString().replace('\\', '/')
            relative.startsWith("ui/") || relative.startsWith("home/") || relative.startsWith("settings/") ||
                relative == "MainActivity.kt" ||
                (relative.startsWith("history/") && (file.fileName.toString().endsWith("Activity.kt") || file.fileName.toString().endsWith("Fragment.kt")))
        }
        assertTrue(uiFiles.isNotEmpty(), "UI source discovery must not silently become empty")
        val backendType = Regex("\\b(DeviceExecutor|DeviceCommandClient|DisplaySessionClient|PrivilegedDisplaySession|SystemTaskBridge|IUserService|AndroidToolExecutor|AndroidComputerDriver|FunctionToolBackend|ResponsesAgentRunner|ScreenshotService|ProcessBuilder|Runtime)\\b")
        val backendImport = Regex("(?m)^\\s*import\\s+com\\.kevinluo\\.autoglm\\.(device|display|computer\\.runtime)\\.")
        val backendProperty = Regex("\\.\\s*(phoneAgent|agentRunner|deviceExecutor|toolExecutor|screenshotService)\\b|get(?:PhoneAgent|AgentRunner|DeviceExecutor|ToolExecutor)\\s*\\(")
        val failures = uiFiles.mapNotNull { file ->
            val code = codeOnly(readSource(file))
            val forbidden = backendType.find(code)?.value ?: backendImport.find(code)?.value ?: backendProperty.find(code)?.value
            forbidden?.let { "${root.relativize(file)} bypasses task commands through $it" }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `conversation snapshot exposes no mutable cursor or usage setters`() {
        val setters = ConversationSnapshot::class.java.methods.filter {
            Modifier.isPublic(it.modifiers) && it.name.startsWith("set") && it.parameterCount > 0
        }
        assertTrue(setters.isEmpty(), "Conversation snapshots must not expose public mutation: ${setters.map { it.name }}")
        val mutableFields = ConversationSnapshot::class.java.declaredFields.filter {
            !Modifier.isStatic(it.modifiers) && !Modifier.isFinal(it.modifiers)
        }
        assertTrue(mutableFields.isEmpty(), "Conversation snapshot fields must remain final")
        val original = ConversationSnapshot("chat", previousResponseId = "r1", totalInputTokens = 12)
        val next = original.copy(previousResponseId = "r2", totalInputTokens = 30)
        assertEquals("r1", original.previousResponseId)
        assertEquals(12L, original.totalInputTokens)
        assertEquals("r2", next.previousResponseId)
    }

    @Test
    fun `device backends cannot decode raw wire argument bags`() {
        val forbidden = Regex("\\b(JsonObject|JsonArray|jsonPrimitive|jsonObject)\\b")
        for (relative in listOf("computer/runtime/FunctionToolBackend.kt", "accessibility/PhoneAccessibilityService.kt")) {
            val code = codeOnly(readSource(appSources.resolve(relative)))
            assertTrue(!forbidden.containsMatchIn(code), "$relative must consume typed commands, not wire JSON")
        }
    }

    @Test
    fun `only the unified tool trace boundary can write invocation history`() {
        val writer = Regex("\\.\\s*recordToolCall\\s*\\(")
        val allowed = setOf("computer/runtime/ToolTrace.kt")
        val failures = sourceFiles(appSources).filter { file ->
            appSources.relativize(file).toString().replace('\\', '/') !in allowed &&
                writer.containsMatchIn(codeOnly(readSource(file)))
        }
        assertTrue(failures.isEmpty(), "Invocation logging bypasses unified dispatch: ${failures.map { root.relativize(it) }}")
    }

    @Test
    fun `active task lifecycle cannot regain parallel boolean flags`() {
        val owner = com.kevinluo.autoglm.core.RunCoordinator::class.java.declaredClasses.single { it.simpleName == "ActiveRun" }
        val fields = owner.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
        assertEquals(1, fields.count { it.type == com.kevinluo.autoglm.core.RunPhase::class.java })
        assertTrue(fields.none { it.type == Boolean::class.javaPrimitiveType || it.type == Boolean::class.javaObjectType },
            "ActiveRun must use its lifecycle stage, not independent flags")
    }

    private fun readSource(file: Path): String = String(Files.readAllBytes(file), Charsets.UTF_8)

    private fun sourceFiles(directory: Path): List<Path> {
        assertTrue(Files.isDirectory(directory), "Required source directory is missing: ${root.relativize(directory)}")
        return Files.walk(directory).use { files ->
            files.iterator().asSequence().filter {
                Files.isRegularFile(it) && it.fileName.toString().substringAfterLast('.') in setOf("kt", "java")
            }.toList()
        }
    }

    companion object {
        private fun repositoryRoot(): Path = generateSequence(Paths.get(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .take(10).firstOrNull {
                Files.isRegularFile(it.resolve("settings.gradle.kts")) && Files.isDirectory(it.resolve("automation-core"))
            } ?: error("Run architecture tests from the repository or its app module")

        /** Strip comments and literals so examples and log text do not look like executable imports. */
        private fun codeOnly(text: String): String {
            val code = StringBuilder(text.length)
            var index = 0
            fun mask(until: Int) {
                while (index < until) {
                    code.append(if (text[index] == '\n') '\n' else ' ')
                    index++
                }
            }
            while (index < text.length) {
                when {
                    text.startsWith("//", index) -> mask(text.indexOf('\n', index).takeIf { it >= 0 } ?: text.length)
                    text.startsWith("/*", index) -> {
                        var cursor = index + 2
                        var depth = 1
                        while (cursor < text.length && depth > 0) {
                            when {
                                text.startsWith("/*", cursor) -> { depth++; cursor += 2 }
                                text.startsWith("*/", cursor) -> { depth--; cursor += 2 }
                                else -> cursor++
                            }
                        }
                        mask(cursor.coerceAtMost(text.length))
                    }
                    text.startsWith("\"\"\"", index) -> {
                        val end = text.indexOf("\"\"\"", index + 3)
                        mask(if (end < 0) text.length else end + 3)
                    }
                    text[index] == '"' || text[index] == '\'' -> {
                        val quote = text[index]
                        var cursor = index + 1
                        while (cursor < text.length) {
                            if (text[cursor] == '\\') { cursor = (cursor + 2).coerceAtMost(text.length); continue }
                            if (text[cursor++] == quote) break
                        }
                        mask(cursor)
                    }
                    else -> code.append(text[index++])
                }
            }
            return code.toString()
        }
    }
}
