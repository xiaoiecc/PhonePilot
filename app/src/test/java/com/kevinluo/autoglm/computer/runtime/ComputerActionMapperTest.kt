package com.kevinluo.autoglm.computer.runtime

import com.kevinluo.autoglm.screenshot.Screenshot
import com.kevinluo.autoglm.util.Point
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ComputerActionMapperTest {
    private val frame = Screenshot("image", 720, 1280, 1080, 1920)
    private fun action(value: String) = ComputerActionMapper.prepare(Json.parseToJsonElement(value).jsonObject, frame)

    @Test fun `pixel coordinates are scaled once without normalized rounding`() {
        assertEquals(Point(540, 960), ComputerActionMapper.point(360, 640, frame))
        assertEquals(Point(1079, 1919), ComputerActionMapper.point(719, 1279, frame))
        assertThrows(IllegalArgumentException::class.java) { ComputerActionMapper.point(720, 0, frame) }
        assertThrows(IllegalArgumentException::class.java) { ComputerActionMapper.point(-1, 0, frame) }
    }

    @Test fun `protected fallback frames cannot drive input`() {
        assertThrows(IllegalArgumentException::class.java) {
            ComputerActionMapper.point(0, 0, frame.copy(isSensitive = true))
        }
    }

    @Test fun `scroll down moves finger upward within the requested region`() {
        val scroll = action("""{"type":"scroll","x":360,"y":640,"scroll_x":0,"scroll_y":500}""") as PreparedAction.Scroll
        assertEquals(1, scroll.gestures.size)
        assertTrue(scroll.gestures.single().from.y > scroll.gestures.single().to.y)
        assertEquals(750, scroll.gestures.single().from.y - scroll.gestures.single().to.y)
    }

    @Test fun `scroll up and horizontal scroll have correct direction`() {
        val up = action("""{"type":"scroll","x":360,"y":640,"scroll_x":0,"scroll_y":-200}""") as PreparedAction.Scroll
        assertTrue(up.gestures.single().from.y < up.gestures.single().to.y)
        val right = action("""{"type":"scroll","x":360,"y":640,"scroll_x":200,"scroll_y":0}""") as PreparedAction.Scroll
        assertTrue(right.gestures.single().from.x > right.gestures.single().to.x)
    }

    @Test fun `unsupported chords hover and curved drag fail before execution`() {
        listOf(
            """{"type":"keypress","keys":["CTRL","A"]}""",
            """{"type":"move","x":1,"y":1}""",
            """{"type":"drag","path":[{"x":1,"y":1},{"x":100,"y":10},{"x":1,"y":200}]}""",
            """{"type":"drag","path":[{"x":1,"y":1},{"x":100,"y":100},{"x":50,"y":50},{"x":200,"y":200}]}""",
            """{"type":"click","x":"1","y":1,"button":"left"}""",
        ).forEach { value -> assertThrows(Exception::class.java) { action(value) } }
    }

    @Test fun `type preserves Unicode and existing text semantics`() {
        assertEquals(PreparedAction.Type("你好🙂"), action("""{"type":"type","text":"你好🙂"}"""))
    }

    @Test fun `mouse modifiers are not silently discarded`() {
        assertThrows(Exception::class.java) {
            action("""{"type":"click","button":"left","x":10,"y":10,"keys":["SHIFT"]}""")
        }
        assertThrows(Exception::class.java) {
            action("""{"type":"click","button":"left","x":10,"y":10,"keys":"CTRL"}""")
        }
        assertEquals(PreparedAction.Tap(Point(15, 15)), action("""{"type":"click","button":"left","x":10,"y":10,"keys":[]}"""))
        assertEquals(PreparedAction.Tap(Point(15, 15)), action("""{"type":"click","button":"left","x":10,"y":10,"keys":null}"""))
    }
}
