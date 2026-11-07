package dev.crumble.android.model

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A file waiting in the composer, already in a form the server accepts. */
data class PendingAttachment(
    val id: UUID = UUID.randomUUID(),
    val name: String,
    val mimeType: String,
    val data: ByteArray,
) {
    val isImage: Boolean
        get() = mimeType.startsWith("image/")

    val outgoing: OutgoingAttachment
        get() = OutgoingAttachment(
            name = name,
            mimeType = mimeType,
            data = Base64.getEncoder().encodeToString(data),
        )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PendingAttachment) return false
        return id == other.id && name == other.name && mimeType == other.mimeType && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

object AttachmentLimits {
    const val COUNT = 8
    const val IMAGE_BYTES = 5 * 1024 * 1024
    const val TEXT_BYTES = 512 * 1024

    /** Larger images need re-encoding, but past this one would take too much of the app's memory to hold. */
    const val IMAGE_SOURCE_BYTES = 32 * 1024 * 1024
    val IMAGE_TYPES: Set<String> = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
    const val IMAGE_SIDE = 2048
}

/**
 * The website's picker, as MIME types: providers report code files under application types, video/mp2t for
 * `.ts`, or octet-stream. `loadAttachment` turns away whatever isn't really text.
 */
val ATTACHMENT_PICKER_TYPES = arrayOf(
    "image/*",
    "text/*",
    "application/json",
    "application/xml",
    "application/javascript",
    "application/x-javascript",
    "application/typescript",
    "application/x-sh",
    "application/x-shellscript",
    "application/yaml",
    "application/x-yaml",
    "application/toml",
    "application/x-ruby",
    "video/mp2t",
    "application/octet-stream",
)

class AttachmentError(message: String) : Exception(message)

/**
 * Reads a file into an attachment. Images the server can't take as they are (HEIC, TIFF, or larger
 * than 5 MB) are re-encoded as JPEG; text files must be UTF-8.
 */
fun loadAttachment(
    name: String,
    mimeTypeHint: String?,
    data: ByteArray,
    jpegReencoder: (ByteArray) -> ByteArray? = ::defaultJpegReencode,
): PendingAttachment {
    val ext = name.substringAfterLast('.', "").lowercase()
    val mime = resolveMimeType(ext, mimeTypeHint)
    if (isImageMime(mime, ext)) {
        if (AttachmentLimits.IMAGE_TYPES.contains(mime) && data.size <= AttachmentLimits.IMAGE_BYTES) {
            return PendingAttachment(name = name, mimeType = mime, data = data)
        }
        val jpeg = jpegReencoder(data) ?: throw AttachmentError("$name couldn't be read as an image.")
        if (jpeg.size > AttachmentLimits.IMAGE_BYTES) {
            throw AttachmentError("$name is larger than 5 MB.")
        }
        val stem = name.substringBeforeLast('.', name)
        return PendingAttachment(name = "$stem.jpg", mimeType = "image/jpeg", data = jpeg)
    }
    if (isTextMime(mime, ext)) {
        if (data.size > AttachmentLimits.TEXT_BYTES) {
            throw AttachmentError("$name is larger than 512 KB.")
        }
        if (!isValidUtf8(data)) {
            throw AttachmentError("$name isn't UTF-8 text.")
        }
        val normalizedMime = if (mime.startsWith("text/") || mime == "application/json") mime else "text/plain"
        return PendingAttachment(name = name, mimeType = normalizedMime, data = data)
    }
    throw AttachmentError("$name can't be attached. Attach images or text files.")
}

fun loadAttachment(
    file: File,
    jpegReencoder: (ByteArray) -> ByteArray? = ::defaultJpegReencode,
): PendingAttachment = loadAttachment(
    name = file.name,
    mimeTypeHint = null,
    data = file.readBytes(),
    jpegReencoder = jpegReencoder,
)

/** Null when the stream holds more than `limit`, so a huge file never reaches memory. */
private fun InputStream.readUpTo(limit: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) return out.toByteArray()
        if (out.size() + count > limit) return null
        out.write(buffer, 0, count)
    }
}

/** Refuses what can't be attached before reading it; a provider's declared size may be missing or wrong. */
internal fun readAttachment(
    name: String,
    mimeTypeHint: String?,
    declaredSize: Long?,
    jpegReencoder: (ByteArray) -> ByteArray? = ::defaultJpegReencode,
    open: () -> InputStream?,
): PendingAttachment {
    val ext = name.substringAfterLast('.', "").lowercase()
    val mime = resolveMimeType(ext, mimeTypeHint)
    val (limit, tooLarge) = when {
        isImageMime(mime, ext) -> AttachmentLimits.IMAGE_SOURCE_BYTES to "$name is larger than 32 MB."
        isTextMime(mime, ext) -> AttachmentLimits.TEXT_BYTES to "$name is larger than 512 KB."
        else -> throw AttachmentError("$name can't be attached. Attach images or text files.")
    }
    if (declaredSize != null && declaredSize > limit) throw AttachmentError(tooLarge)
    val bytes = try {
        val stream = open() ?: throw AttachmentError("$name couldn't be read.")
        stream.use { it.readUpTo(limit) } ?: throw AttachmentError(tooLarge)
    } catch (_: OutOfMemoryError) {
        throw AttachmentError("$name couldn't be read.")
    }
    return loadAttachment(name = name, mimeTypeHint = mimeTypeHint, data = bytes, jpegReencoder = jpegReencoder)
}

fun loadAttachment(
    resolver: ContentResolver,
    uri: Uri,
): PendingAttachment {
    val (displayName, size) = queryOpenable(resolver, uri)
    val name = displayName ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
    return readAttachment(name, resolver.getType(uri), size) { resolver.openInputStream(uri) }
}

private fun queryOpenable(resolver: ContentResolver, uri: Uri): Pair<String?, Long?> {
    return try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let(cursor::getString)
            val size = cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)
            name to size
        } ?: (null to null)
    } catch (_: Exception) {
        null to null
    }
}

private fun resolveMimeType(ext: String, hint: String?): String {
    if (!hint.isNullOrBlank() && hint != "application/octet-stream") return hint.lowercase()
    return when (ext) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heic"
        "tif", "tiff" -> "image/tiff"
        "bmp" -> "image/bmp"
        "txt", "log" -> "text/plain"
        "md", "markdown" -> "text/markdown"
        "csv" -> "text/csv"
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "json" -> "application/json"
        "xml" -> "text/xml"
        "yaml", "yml" -> "text/yaml"
        "kt", "kts", "java", "swift", "py", "js", "ts", "tsx", "jsx", "go", "rs", "rb", "c", "cpp", "h", "sh", "sql", "toml" -> "text/plain"
        else -> {
            val fromSystem = try {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            } catch (_: Throwable) {
                null
            }
            fromSystem ?: "application/octet-stream"
        }
    }
}

private fun isImageMime(mime: String, ext: String): Boolean {
    return mime.startsWith("image/") || ext in setOf("png", "jpg", "jpeg", "gif", "webp", "heic", "heif", "tif", "tiff", "bmp")
}

private fun isTextMime(mime: String, ext: String): Boolean {
    if (mime.startsWith("text/") || mime == "application/json" || mime == "application/xml") return true
    return ext in setOf(
        "txt", "md", "markdown", "json", "xml", "yaml", "yml", "csv", "log", "html", "htm", "css",
        "kt", "kts", "java", "swift", "py", "js", "ts", "tsx", "jsx", "go", "rs", "rb", "c", "cpp", "h", "sh", "sql", "toml",
    )
}

private fun isValidUtf8(data: ByteArray): Boolean {
    val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        decoder.decode(ByteBuffer.wrap(data))
        true
    } catch (_: CharacterCodingException) {
        false
    }
}

private fun defaultJpegReencode(data: ByteArray): ByteArray? {
    return try {
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
        val maxDim = max(bitmap.width, bitmap.height)
        val scale = min(1.0f, AttachmentLimits.IMAGE_SIDE.toFloat() / maxDim.toFloat())
        val width = max(1, (bitmap.width * scale).roundToInt())
        val height = max(1, (bitmap.height * scale).roundToInt())
        val scaled = if (width != bitmap.width || height != bitmap.height) {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        } else {
            bitmap
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        out.toByteArray()
    } catch (_: Throwable) {
        null
    }
}
