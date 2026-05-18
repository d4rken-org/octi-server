package eu.darken.octi.server.common

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ParseCapabilitiesHeaderTest {

    @Test
    fun `null or blank returns null`() {
        parseCapabilitiesHeader(null).shouldBeNull()
        parseCapabilitiesHeader("").shouldBeNull()
        parseCapabilitiesHeader("   ").shouldBeNull()
    }

    @Test
    fun `valid array decodes into a set`() {
        val raw = """["encryption:AES256_GCM_SIV","encryption:_reported","encryption:AES256_SIV"]"""
        parseCapabilitiesHeader(raw)!! shouldContainExactlyInAnyOrder listOf(
            "encryption:AES256_GCM_SIV",
            "encryption:_reported",
            "encryption:AES256_SIV",
        )
    }

    @Test
    fun `empty array decodes to empty set`() {
        parseCapabilitiesHeader("[]") shouldBe emptySet()
    }

    @Test
    fun `non-array element rejected`() {
        parseCapabilitiesHeader("\"a string\"").shouldBeNull()
        parseCapabilitiesHeader("42").shouldBeNull()
        parseCapabilitiesHeader("{\"a\":1}").shouldBeNull()
    }

    @Test
    fun `malformed JSON rejected`() {
        parseCapabilitiesHeader("not json").shouldBeNull()
        parseCapabilitiesHeader("[unclosed").shouldBeNull()
    }

    @Test
    fun `array with non-string element rejected`() {
        parseCapabilitiesHeader("""["encryption:AES256_GCM_SIV",42]""").shouldBeNull()
        parseCapabilitiesHeader("""["encryption:AES256_GCM_SIV",null]""").shouldBeNull()
    }

    @Test
    fun `array with bad tag shape rejected`() {
        parseCapabilitiesHeader("""["bad tag"]""").shouldBeNull()
        parseCapabilitiesHeader("""[":value"]""").shouldBeNull()
        parseCapabilitiesHeader("""["Encryption:GCM_SIV"]""").shouldBeNull() // uppercase ns
        parseCapabilitiesHeader("""["encryption:"]""").shouldBeNull()
    }

    @Test
    fun `oversized array rejected`() {
        val tags = (0 until MAX_CAPABILITY_TAGS + 1).joinToString(",") { "\"ns:value$it\"" }
        parseCapabilitiesHeader("[$tags]").shouldBeNull()
    }

    @Test
    fun `tag exceeding max length rejected`() {
        val tooLong = "encryption:" + "a".repeat(MAX_CAPABILITY_TAG_LENGTH)
        parseCapabilitiesHeader("""["$tooLong"]""").shouldBeNull()
    }

    @Test
    fun `oversized header rejected before parse`() {
        // Cheap defense — caller doesn't pay parse cost on hostile input.
        parseCapabilitiesHeader("[" + "x".repeat(MAX_CAPABILITY_HEADER_LENGTH + 1) + "]").shouldBeNull()
    }
}
