package com.wifimouse.app.vision

import java.nio.ByteBuffer

/**
 * Shrinks the camera's luma plane down to the small frame the tracker works on.
 *
 * Only the Y plane is read — colour is irrelevant for matching texture, and
 * skipping the chroma planes avoids a full YUV conversion per frame. Averaging
 * rather than picking single pixels matters: it suppresses sensor noise and
 * aliasing, which otherwise show up as phantom pointer drift.
 */
class LumaDownsampler(private val outWidth: Int, private val outHeight: Int) {

    /**
     * @param rowStride   bytes per source row; cameras routinely pad this
     *                    beyond the image width, and ignoring it shears the image
     * @param pixelStride bytes between horizontally adjacent samples
     */
    fun downsample(
        source: ByteBuffer,
        sourceWidth: Int,
        sourceHeight: Int,
        rowStride: Int,
        pixelStride: Int,
        destination: ByteArray,
    ) {
        require(destination.size >= outWidth * outHeight) { "destination buffer is too small" }

        for (outY in 0 until outHeight) {
            val startY = outY * sourceHeight / outHeight
            val endY = maxOf(startY + 1, (outY + 1) * sourceHeight / outHeight)
            val destinationRow = outY * outWidth

            for (outX in 0 until outWidth) {
                val startX = outX * sourceWidth / outWidth
                val endX = maxOf(startX + 1, (outX + 1) * sourceWidth / outWidth)

                var total = 0
                var samples = 0
                var y = startY
                while (y < endY) {
                    val rowStart = y * rowStride
                    var x = startX
                    while (x < endX) {
                        total += source.get(rowStart + x * pixelStride).toInt() and 0xFF
                        samples++
                        x += 2 // every other column is plenty and halves the work
                    }
                    y += 2
                }
                destination[destinationRow + outX] = (total / samples).toByte()
            }
        }
    }
}
