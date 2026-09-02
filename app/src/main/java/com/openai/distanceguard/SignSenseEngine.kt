package com.openai.distanceguard

import androidx.camera.core.ImageProxy
import java.io.Closeable

/**
 * V15.2: traffic-sign/OCR recognition is intentionally removed.
 *
 * This no-op shell remains only for source compatibility with legacy UI
 * references. It performs no image copy, OCR, worker thread or inference.
 */
class SignSenseEngine(
    @Suppress("UNUSED_PARAMETER")
    onConfirmed: (TrafficSignObservation) -> Unit,
) : Closeable {
    @Suppress("UNUSED_PARAMETER")
    fun submit(image: ImageProxy, timestampNs: Long): Boolean = false

    override fun close() = Unit
}
