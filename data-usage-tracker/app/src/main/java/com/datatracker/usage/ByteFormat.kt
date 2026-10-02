package com.datatracker.usage

/** Uses 1024-based GB (GiB), matching how most Android system UI displays data usage. */
object ByteFormat {
    private const val BYTES_PER_GB = 1024.0 * 1024.0 * 1024.0

    fun toGb(bytes: Long): Double = bytes / BYTES_PER_GB

    fun gbToBytes(gb: Double): Long = (gb * BYTES_PER_GB).toLong()

    fun format(bytes: Long): String {
        val gb = toGb(bytes)
        return if (gb < 0.01 && bytes > 0) {
            "%.0f MB".format(bytes / (1024.0 * 1024.0))
        } else {
            "%.2f GB".format(gb)
        }
    }
}
