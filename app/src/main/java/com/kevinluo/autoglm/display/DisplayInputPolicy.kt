package com.kevinluo.autoglm.display

/** Numeric input and generated capture names are validated before reaching system commands. */
internal object DisplayInputPolicy {
    private val backgroundKeys = setOf(4, 19, 20, 21, 22, 23, 61, 62, 66, 67, 92, 93, 111, 112, 122, 123, 124, 125)
    fun arguments(displayId: Int, background: Boolean, operation: Int, values: IntArray): Array<String> {
        require(displayId >= 0 && (!background || displayId > 0))
        val verb = when (operation) {
            DisplaySessionClient.TAP -> {
                require(values.size == 2 && values.all { it in 0..1000000 }); "tap"
            }
            DisplaySessionClient.SWIPE -> {
                require(values.size == 5 && values.take(4).all { it in 0..1000000 } && values[4] in 1..5000); "swipe"
            }
            DisplaySessionClient.KEY -> {
                require(values.size == 1 && values[0] in 0..1000)
                check(!background || values[0] in backgroundKeys) { "后台不允许影响主屏的系统按键" }; "keyevent"
            }
            else -> error("Unsupported display input")
        }
        return arrayOf("input", "-d", "$displayId", verb) + values.map(Int::toString)
    }
    fun screenshotPath(imageId: String): String {
        require(imageId.matches(Regex("[a-f0-9]{32}"))) { "Invalid screenshot identity" }
        return "/data/local/tmp/screenshot_$imageId.png"
    }
}
