@file:OptIn(ExperimentalComposeUiApi::class)

package coil3.decode

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.OhosImageDecodeContext
import androidx.compose.ui.res.OhosImageLoadConfig
import androidx.compose.ui.res.loadImageBitmapFromFileWithNativeDma
import androidx.compose.ui.res.loadImageBitmapWithNativeDma
import coil3.Canvas as CoilCanvas
import coil3.Extras
import coil3.Image
import coil3.ImageLoader
import coil3.fetch.SourceFetchResult
import coil3.getExtra
import coil3.request.Options
import coil3.size.Scale
import coil3.size.isOriginal
import coil3.size.pxOrElse
import coil3.util.Logger
import coil3.util.println as logLine
import okio.use

internal val OhosImageDecodeContextKey = Extras.Key<OhosImageDecodeContext?>(default = null)

private const val TAG = "OhosNativeDmaDecoder"

internal class OhosComposeImage(
    private val bitmap: ImageBitmap,
) : Image {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height
    override val size: Long get() = width.toLong() * height * 4L
    override val shareable: Boolean get() = true

    override fun draw(canvas: CoilCanvas) {
        // Defense-in-depth: never crash the render thread with an invalid bitmap
        // (e.g. its native buffer was released after decoding).
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            logLine(Logger.Level.Warn, TAG, "Skipped drawing an invalid bitmap ${bitmap.width}x${bitmap.height}")
            return
        }
        canvas.asComposeCanvas().drawImage(bitmap, Offset.Zero, Paint())
    }
}

internal class OhosNativeDmaDecoder(
    private val result: SourceFetchResult,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult? {
        val targetWidth = options.targetWidthOrNull()
        val targetHeight = options.targetHeightOrNull()
        val contentScale = options.scale.toContentScale()
        val decodeContext = options.getExtra(OhosImageDecodeContextKey)
        val file = result.source.fileOrNull()

        val bitmap = if (file != null) {
            val sourceKey = options.diskCacheKey ?: file.toString()
            decodeWithNativeDma {
                loadImageBitmapFromFileWithNativeDma(
                    filePath = file.toString(),
                    decodeContext = decodeContext,
                    targetWidthPx = targetWidth,
                    targetHeightPx = targetHeight,
                    sourceKey = sourceKey,
                    contentScale = contentScale,
                )
            }
        } else {
            val bytes = result.source.source().peek().use { it.readByteArray() }
            val sourceKey = options.diskCacheKey ?: "coil-bytes:${bytes.contentHashCode()}"
            decodeWithNativeDma {
                loadImageBitmapWithNativeDma(
                    bytes = bytes,
                    decodeContext = decodeContext,
                    targetWidthPx = targetWidth,
                    targetHeightPx = targetHeight,
                    sourceKey = sourceKey,
                    contentScale = contentScale,
                )
            }
        } ?: return null

        return DecodeResult(
            image = OhosComposeImage(bitmap),
            isSampled = targetWidth != null && targetHeight != null,
        )
    }

    /**
     * Run the native DMA decode; return null if it fails or produces an invalid bitmap.
     * Returning null makes the engine fall back to the next registered decoder
     * (the default [coil3.decode.SkiaImageDecoder]).
     */
    private suspend fun decodeWithNativeDma(decode: suspend () -> ImageBitmap?): ImageBitmap? {
        val bitmap = runCatching { decode() }
            .onFailure { e ->
                logLine(Logger.Level.Warn, TAG, "Native DMA decode failed, falling back to the default decoder: $e")
            }
            .getOrNull()
            ?: return null

        if (bitmap.width <= 0 || bitmap.height <= 0) {
            logLine(
                Logger.Level.Warn,
                TAG,
                "Native DMA decode produced an invalid bitmap ${bitmap.width}x${bitmap.height}, " +
                    "falling back to the default decoder",
            )
            return null
        }
        return bitmap
    }

    internal class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            if (!OhosImageLoadConfig.useNativeDmaDecodeWhenAvailable) return null
            val mimeType = result.mimeType
            if (mimeType != null && mimeType !in SUPPORTED_MIME_TYPES) return null
            return OhosNativeDmaDecoder(result, options)
        }

        private companion object {
            val SUPPORTED_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
        }
    }
}

private fun Options.targetWidthOrNull(): Int? {
    if (size.isOriginal) return null
    return size.width.pxOrElse { 0 }.takeIf { it > 0 }
}

private fun Options.targetHeightOrNull(): Int? {
    if (size.isOriginal) return null
    return size.height.pxOrElse { 0 }.takeIf { it > 0 }
}

private fun Scale.toContentScale(): ContentScale = when (this) {
    Scale.FILL -> ContentScale.Crop
    Scale.FIT -> ContentScale.Fit
}
