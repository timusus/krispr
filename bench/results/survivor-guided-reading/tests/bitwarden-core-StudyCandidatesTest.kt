package com.bitwarden.core.study

import com.bitwarden.core.data.repository.util.SpecialCharWithPrecedenceComparator
import com.bitwarden.core.data.util.toFormattedDateStyle
import com.bitwarden.core.di.CoreModule
import com.bitwarden.core.util.isOverFiveMinutesOld
import io.mockk.mockk
import kotlinx.serialization.Serializable
import com.bitwarden.core.data.serializer.InstantSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.sign

/** One test per pooled candidate; a failure means the candidate's claim holds on main. */
class StudyCandidatesTest {
    private val clock: Clock = Clock.fixed(Instant.parse("2023-10-27T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `B1 an instant five and a half minutes old is over five minutes old`() {
        assertTrue(clock.instant().minusSeconds(330).isOverFiveMinutesOld(clock))
    }

    @Serializable
    private data class InstantData(@Serializable(InstantSerializer::class) val dataAsInstant: Instant)

    @Test
    fun `B2 a timestamp with nine fraction digits deserializes`() {
        val json = CoreModule.providesJson(buildInfoManager = mockk(relaxed = true))
        assertEquals(
            InstantData(Instant.ofEpochSecond(1_690_906_383L, 123_456_789L)),
            json.decodeFromString<InstantData>("""{"dataAsInstant": "2023-08-01T16:13:03.123456789Z"}"""),
        )
    }

    @Test
    fun `B3 month names follow the given locale`() {
        val default = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            assertEquals(
                "December 10, 2023",
                Instant.parse("2023-12-10T15:30:00Z").toFormattedDateStyle(FormatStyle.LONG, Locale.US, clock),
            )
        } finally {
            Locale.setDefault(default)
        }
    }

    @Test
    fun `B4 the comparator is transitive for I, dotted I and J`() {
        val c = SpecialCharWithPrecedenceComparator
        val a = c.compare("I", "İ")
        val b = c.compare("I", "J")
        val d = c.compare("İ", "J")
        assertTrue(a != 0 || b.sign == d.sign, "compare(I,İ)=$a compare(I,J)=$b compare(İ,J)=$d")
    }
}
