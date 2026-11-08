package com.kevinluo.autoglm.screenshot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.kevinluo.autoglm.IUserService
import com.kevinluo.autoglm.device.DeviceCommandClient
import com.kevinluo.autoglm.util.ErrorHandler
import com.kevinluo.autoglm.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Data class representing a captured screenshot.
 *
 * Contains the base64-encoded image data along with metadata about the capture.
 * The image is typically in WebP format for optimal compression.
 *
 * @property base64Data Base64-encoded image data (typically WebP format)
 * @property width Width of the screenshot in pixels (after scaling)
 * @property height Height of the screenshot in pixels (after scaling)
 * @property originalWidth Original screen width in pixels (before scaling)
 * @property originalHeight Original screen height in pixels (before scaling)
 * @property isSensitive Flag indicating if the screen was sensitive (e.g., password input)
 *
 */
data class Screenshot(
    val base64Data: String,
    val width: Int,
    val height: Int,
    val originalWidth: Int = width,
    val originalHeight: Int = height,
    val isSensitive: Boolean = false,
    val placementGeneration: Long = 0,
)

/**
 * Interface for controlling floating window visibility during screenshot capture.
 *
 * The floating window must be hidden before capture to avoid appearing in screenshots.
 * Implementations should handle the visibility state transitions safely.
 *
 */
interface FloatingWindowController {
    /**
     * Hides the floating window from the screen.
     */
    fun hide()

    /**
     * Shows the floating window on the screen.
     */
    fun show()

    /**
     * Shows the floating window and brings it to the front of other windows.
     */
    fun showAndBringToFront()

    /**
     * Checks if the floating window is currently visible.
     *
     * @return true if the window is visible, false otherwise
     */
    fun isVisible(): Boolean
}

/**
 * Service for capturing device screenshots using Shizuku shell commands.
 *
 * Manages floating window visibility during capture to ensure the window
 * doesn't appear in captured screenshots. Screenshots are captured in PNG format,
 * then scaled and converted to WebP for optimal file size.
 *
 * @param userService Shizuku user service for executing shell commands
 * @param floatingWindowControllerProvider Provider function for the floating window controller
 *
 */
class ScreenshotService(
    userService: IUserService,
    private val backgroundCapture: ((String) -> String?)? = null,
    private val floatingWindowControllerProvider: () -> FloatingWindowController? = { null },
) {
    private val commandClient = DeviceCommandClient(userService)
    companion object {
        private const val TAG = "ScreenshotService"
        private const val HIDE_DELAY_MS = 200L
        private const val SHOW_DELAY_MS = 100L
        private const val FALLBACK_WIDTH = 1080
        private const val FALLBACK_HEIGHT = 1920

        // Screenshot scaling settings - use max dimensions instead of fixed scale factor
        // This ensures consistent output size regardless of device resolution
        private const val MAX_WIDTH = 720 // Max width after scaling
        private const val MAX_HEIGHT = 1280 // Max height after scaling

        // Base64 output chunk size for reading (safe for Binder)
        private const val BASE64_CHUNK_SIZE = 480000
        private val screenshotCommands = ScreenshotCommandGate()
    }

    /**
     * Captures the current screen content.
     *
     * Uses try-finally pattern to ensure floating window is always restored,
     * even if an exception occurs during capture. The screenshot is scaled
     * and compressed to WebP format for optimal size.
     *
     * @return Screenshot object containing the captured image data and metadata
     *
     */
    suspend fun capture(): Screenshot = withContext(Dispatchers.IO) {
        val floatingWindowController = floatingWindowControllerProvider()
        Logger.d(TAG, "Starting screenshot capture, window visible: ${floatingWindowController?.isVisible()}")

        try {
            val result = captureScreen()
            Logger.logScreenshot(result.width, result.height, result.isSensitive)
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: ScreenshotCaptureException) {
            throw e
        } catch (e: Exception) {
            val handledError = ErrorHandler.handleScreenshotError(e.message ?: "Unknown error", false, e)
            Logger.e(TAG, ErrorHandler.formatErrorForLog(handledError), e)
            createFallbackScreenshot()
        }
    }

    /**
     * Captures the screen using Shizuku shell command.
     *
     * Executes screencap command, scales down the image if needed,
     * and converts to WebP format for smaller file size.
     *
     * @return Screenshot object with captured image data, or fallback screenshot on failure
     *
     */
    private suspend fun captureScreen(): Screenshot = withContext(Dispatchers.IO) {
        try {
            Logger.d(TAG, "Executing screencap command")

            val pngData = executeScreencapToBytes()

            if (pngData == null || pngData.isEmpty()) {
                Logger.w(TAG, "Failed to capture screenshot, returning fallback")
                return@withContext createFallbackScreenshot()
            }

            Logger.d(TAG, "PNG data captured: ${pngData.size} bytes")

            // Decode PNG to bitmap
            var bitmap = BitmapFactory.decodeByteArray(pngData, 0, pngData.size)
            if (bitmap == null) {
                Logger.w(TAG, "Failed to decode PNG, returning fallback")
                return@withContext createFallbackScreenshot()
            }

            val originalWidth = bitmap.width
            val originalHeight = bitmap.height

            // Calculate scaled dimensions based on max size constraints
            val (scaledWidth, scaledHeight) = calculateOptimalDimensions(originalWidth, originalHeight)

            val imageData = try {
                if (scaledWidth != originalWidth || scaledHeight != originalHeight) {
                    val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
                    if (scaledBitmap !== bitmap) bitmap.recycle()
                    bitmap = scaledBitmap
                    Logger.d(TAG, "Scaled from ${originalWidth}x$originalHeight to ${scaledWidth}x$scaledHeight")
                }
                ScreenshotCompression.encode(bitmap)
            } finally {
                bitmap.recycle()
            }
            val compressionRatio = 100L * imageData.size / pngData.size
            Logger.d(TAG, "WebP: ${imageData.size} bytes ($compressionRatio% of captured PNG)")

            val base64Data = encodeToBase64(imageData)
            Logger.d(TAG, "Screenshot captured: ${scaledWidth}x$scaledHeight, base64 length: ${base64Data.length}")

            Screenshot(
                base64Data = base64Data,
                width = scaledWidth,
                height = scaledHeight,
                originalWidth = originalWidth,
                originalHeight = originalHeight,
                isSensitive = false,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ScreenshotCaptureException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Screenshot capture failed", e)
            createFallbackScreenshot()
        }
    }

    /**
     * Calculates optimal dimensions based on max size constraints.
     *
     * Maintains aspect ratio while ensuring the image fits within MAX_WIDTH x MAX_HEIGHT.
     * If the original image is already smaller, returns original dimensions.
     *
     * @param originalWidth Original image width in pixels
     * @param originalHeight Original image height in pixels
     * @return Pair of (scaledWidth, scaledHeight) maintaining aspect ratio
     *
     */
    private fun calculateOptimalDimensions(originalWidth: Int, originalHeight: Int): Pair<Int, Int> {
        // If already within limits, no scaling needed
        if (originalWidth <= MAX_WIDTH && originalHeight <= MAX_HEIGHT) {
            Logger.d(TAG, "Image already within limits: ${originalWidth}x$originalHeight")
            return originalWidth to originalHeight
        }

        // Calculate scale ratios for both dimensions
        val widthRatio = MAX_WIDTH.toFloat() / originalWidth
        val heightRatio = MAX_HEIGHT.toFloat() / originalHeight

        // Use the smaller ratio to ensure both dimensions fit within limits
        val ratio = minOf(widthRatio, heightRatio)

        val scaledWidth = (originalWidth * ratio).toInt()
        val scaledHeight = (originalHeight * ratio).toInt()

        Logger.d(TAG, "Scaling with ratio $ratio: ${originalWidth}x$originalHeight -> ${scaledWidth}x$scaledHeight")
        return scaledWidth to scaledHeight
    }

    /**
     * Executes screencap command and returns raw bytes.
     *
     * Uses sequential chunk reading for large files to avoid Binder buffer overflow.
     * The screenshot is captured as PNG, converted to base64, then decoded back to bytes.
     *
     * @return Raw PNG bytes of the screenshot, or null if capture failed
     *
     */
    private suspend fun executeScreencapToBytes(): ByteArray? = coroutineScope {
        val imageId = java.util.UUID.randomUUID().toString().replace("-", "")
        val pngFile = "/data/local/tmp/screenshot_$imageId.png"
        val base64File = "$pngFile.b64"

        try {
            Logger.d(TAG, "Attempting screenshot capture")
            val startTime = System.currentTimeMillis()

            // Capture screenshot and pipe to base64
            // Only capturing pixels needs a hidden overlay. Reads, decoding, compression and
            // cleanup happen after restoration, so a slow encoder cannot hide the controls.
            val captureResult = withOverlayHidden(
                floatingWindowControllerProvider(), hideDelayMs = HIDE_DELAY_MS,
                showDelayMs = SHOW_DELAY_MS, bringToFront = true,
            ) {
                val capturedPath = backgroundCapture?.let { capture -> screenshotCommands.execute(9000) { capture(imageId) } }
                check(capturedPath == null || capturedPath == pngFile) { "Invalid capture destination" }
                val script = "set -e\n" + (if (capturedPath == null) "screencap -p $pngFile\n" else "") +
                    "base64 $pngFile > $base64File\nstat -c %s $base64File"
                screenshotCommand("CAPTURE", listOf("sh", "-c", script), 8000)
            }

            val captureTime = System.currentTimeMillis() - startTime
            Logger.d(TAG, "Screenshot capture took ${captureTime}ms")

            // Parse base64 file size from output
            val base64Size =
                captureResult
                    .lines()
                    .firstOrNull { it.trim().all { c -> c.isDigit() } }
                    ?.trim()
                    ?.toLongOrNull() ?: 0L

            if (base64Size !in 1..16_000_000L) {
                Logger.w(TAG, "Base64 file not created or empty")
                return@coroutineScope null
            }

            Logger.d(TAG, "Base64 file size: $base64Size bytes")

            // Read base64 file
            val readStartTime = System.currentTimeMillis()
            val readDeadline = System.nanoTime() + 12_000_000_000L
            suspend fun readChunk(argv: List<String>): String {
                val remaining = (readDeadline - System.nanoTime()) / 1_000_000
                if (remaining < 100) throw ScreenshotCaptureException("截图读取超时，任务已停止；悬浮窗已恢复")
                return screenshotCommand("READ", argv, minOf(3000L, remaining))
            }
            val base64Data: String

            if (base64Size <= BASE64_CHUNK_SIZE) {
                // Small file - read in one go
                val result = readChunk(listOf("cat", base64File))
                base64Data =
                    result
                        .lines()
                        .filter { line ->
                            line.isNotBlank()
                        }.joinToString("")
            } else {
                // Large file - read chunks sequentially to avoid Binder buffer overflow
                val chunkCount = ((base64Size + BASE64_CHUNK_SIZE - 1) / BASE64_CHUNK_SIZE).toInt()
                Logger.d(TAG, "Reading $chunkCount chunks sequentially")

                val chunks = mutableListOf<String>()

                for (index in 0 until chunkCount) {
                    val offset = index.toLong() * BASE64_CHUNK_SIZE
                    val remaining = base64Size - offset
                    val currentChunkSize = minOf(BASE64_CHUNK_SIZE.toLong(), remaining)

                    val chunkResult =
                        readChunk(
                            listOf("sh", "-c", "tail -c +${offset + 1} $base64File | head -c $currentChunkSize"),
                        )

                    val chunkData =
                        chunkResult
                            .lines()
                            .filter { line ->
                                line.isNotBlank()
                            }.joinToString("")

                    chunks.add(chunkData)
                }

                base64Data = chunks.joinToString("")
            }

            val readTime = System.currentTimeMillis() - readStartTime
            Logger.d(TAG, "Base64 read took ${readTime}ms, total length: ${base64Data.length}")

            if (base64Data.isBlank()) {
                Logger.w(TAG, "No base64 data read")
                return@coroutineScope null
            }

            Base64.decode(base64Data, Base64.DEFAULT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ScreenshotCaptureException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to capture screenshot to bytes", e)
            null
        } finally {
            // Clean up temp files
            try {
                withContext(NonCancellable) {
                    screenshotCommand("CLEANUP", listOf("rm", "-f", pngFile, base64File), 1000)
                }
            } catch (_: Exception) {
                // Ignore cleanup errors
            }
        }
    }

    private suspend fun screenshotCommand(phase: String, argv: List<String>, timeoutMs: Long): String {
        Logger.d(TAG, "Screenshot IPC phase=$phase started")
        val started = System.nanoTime()
        val result = try {
            screenshotCommands.execute(timeoutMs + 1000) {
                commandClient.executeArgsBlocking(argv, timeoutMs)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: ScreenshotCaptureException) {
            Logger.w(TAG, "Screenshot IPC phase=$phase stopped at deadline or busy gate")
            throw error
        } catch (_: Exception) {
            throw ScreenshotCaptureException("截图设备连接已失效，任务已停止；请检查 Shizuku 后重试")
        }
        requireSuccessfulScreenshotCommand(result)
        Logger.d(TAG, "Screenshot IPC phase=$phase completed in ${(System.nanoTime() - started) / 1_000_000}ms")
        return result.stdout
    }

    /**
     * Creates a fallback black screenshot in WebP format.
     *
     * Used when screenshot capture fails to provide a valid Screenshot object.
     * The fallback is marked as sensitive to indicate capture failure.
     *
     * @return Screenshot object with a black image and isSensitive=true
     *
     */
    private fun createFallbackScreenshot(): Screenshot {
        val bitmap = Bitmap.createBitmap(FALLBACK_WIDTH, FALLBACK_HEIGHT, Bitmap.Config.ARGB_8888)

        val data = try { ScreenshotCompression.encode(bitmap) } finally { bitmap.recycle() }
        val base64Data = Base64.encodeToString(data, Base64.NO_WRAP)

        return Screenshot(
            base64Data = base64Data,
            width = FALLBACK_WIDTH,
            height = FALLBACK_HEIGHT,
            isSensitive = true,
        )
    }

    /**
     * Encodes byte array to base64 string.
     *
     * @param data Byte array to encode
     * @return Base64-encoded string without line wrapping
     *
     */
    fun encodeToBase64(data: ByteArray): String = Base64.encodeToString(data, Base64.NO_WRAP)

    /**
     * Decodes base64 string to byte array.
     *
     * @param base64Data Base64-encoded string to decode
     * @return Decoded byte array
     *
     */
    fun decodeFromBase64(base64Data: String): ByteArray = Base64.decode(base64Data, Base64.DEFAULT)

    /**
     * Decodes base64 screenshot data to a Bitmap.
     *
     * @param base64Data Base64-encoded image data
     * @return Decoded Bitmap, or null if decoding fails
     *
     */
    fun decodeScreenshotToBitmap(base64Data: String): Bitmap? = try {
        val bytes = decodeFromBase64(base64Data)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) {
        null
    }

    /**
     * Encodes a Bitmap to a WebP base64 string.
     *
     * @param bitmap Bitmap to encode
     * @return Base64-encoded string of the compressed image
     *
     */
    fun encodeBitmapToBase64(bitmap: Bitmap): String = encodeToBase64(ScreenshotCompression.encode(bitmap))

    /**
     * Creates a Screenshot object from a Bitmap.
     *
     * @param bitmap Source bitmap to convert
     * @param isSensitive Whether the content is sensitive (default: false)
     * @return Screenshot object with encoded image data and dimensions
     *
     */
    fun createScreenshotFromBitmap(bitmap: Bitmap, isSensitive: Boolean = false): Screenshot = Screenshot(
        base64Data = encodeBitmapToBase64(bitmap),
        width = bitmap.width,
        height = bitmap.height,
        isSensitive = isSensitive,
    )
}
