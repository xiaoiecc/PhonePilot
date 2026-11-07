package com.kevinluo.autoglm.computer.protocol

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow

class UsageTest : FunSpec({
    test("commentary alone is not task completion") {
        val error = shouldThrow<ComputerProtocolException> {
            ComputerProtocol.parseResponse("""{"id":"r","status":"completed","output":[{"type":"message","role":"assistant","status":"completed","phase":"commentary","content":[{"type":"output_text","text":"Working"}]}]}""")
        }
        error.message shouldBe "Responses returned no action or answer"
    }
    test("usage preserves measured input output and cache counts") {
        val result = ComputerProtocol.parseResponse("""
            {"id":"r","status":"completed","output":[{"type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"OK"}]}],
            "usage":{"input_tokens":1500,"output_tokens":200,"input_tokens_details":{"cached_tokens":1024}}}
        """.trimIndent())
        result.inputTokens shouldBe 1500
        result.outputTokens shouldBe 200
        result.cachedInputTokens shouldBe 1024
    }
})
