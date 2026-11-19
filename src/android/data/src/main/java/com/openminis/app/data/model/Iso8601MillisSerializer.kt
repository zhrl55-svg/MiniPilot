package com.openminis.app.data.model

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

object Iso8601MillisSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Iso8601Millis", PrimitiveKind.STRING)

    private fun fmt() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override fun serialize(encoder: Encoder, value: Long) {
        encoder.encodeString(fmt().format(value))
    }

    override fun deserialize(decoder: Decoder): Long {
        // Tolerate both a JSON string (ISO-8601) and a JSON number (legacy
        // epoch millis). Use JsonDecoder to peek at the actual element type so
        // an old numeric package doesn't throw.
        val jd = decoder as? JsonDecoder
            ?: return runCatching { fmt().parse(decoder.decodeString())?.time }.getOrNull() ?: 0L
        val el = jd.decodeJsonElement()
        val prim = el as? JsonPrimitive ?: return 0L
        prim.longOrNull?.let { return it } // legacy numeric epoch millis
        val s = prim.content
        return runCatching { fmt().parse(s)?.time }.getOrNull() ?: 0L
    }
}

/**
 * [T-android-provider-iso8601-wire] Nullable peer of [Iso8601MillisSerializer]
 * for optional timestamps (e.g. `ModelEntry.userModifiedAt`, which iOS decodes
 * as `Date?`). null round-trips as JSON null; otherwise identical rules —
 * writes ISO-8601, reads ISO strings or legacy epoch-millis numbers.
 */
