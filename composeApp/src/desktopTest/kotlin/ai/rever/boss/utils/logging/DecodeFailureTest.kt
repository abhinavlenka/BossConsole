package ai.rever.boss.utils.logging

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [decodeFailure] against real exceptions from the kotlinx version this build uses, not against
 * hand-written messages: each case first checks that the raw message really does carry the private
 * text, so a kotlinx upgrade that changes the wording shows up here instead of silently.
 */
class DecodeFailureTest {
    @Serializable
    private data class Page(
        val url: String,
        val visitCount: Int = 0,
    )

    @Serializable
    private data class Zoom(
        val levels: Map<String, Double> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    private inline fun failureOf(decode: () -> Unit): SerializationException {
        try {
            decode()
        } catch (e: SerializationException) {
            return e
        }
        fail("the input was meant to fail to decode")
    }

    private fun assertWithheld(
        secret: String,
        error: SerializationException,
    ): Map<String, Any?> {
        assertTrue(secret in error.message.orEmpty(), "premise: kotlinx quotes it: ${error.message}")
        val fields = decodeFailure(error)
        assertFalse(secret in fields.toString(), "the log fields must not carry it: $fields")
        return fields
    }

    @Test
    fun `a torn file is reported by type and path without the document kotlinx appends`() {
        val secret = "token=s3cr3t-recent-page"
        val error = failureOf { json.decodeFromString<List<Page>>("""[{"url":"https://a.example/?$secret"""") }

        val fields = assertWithheld(secret, error)

        assertEquals("JsonDecodingException", fields["decodeFailure"])
        // A tear at the end of the file carries no offset in kotlinx's wording, only the path.
        assertEquals("$[0]", fields["path"])
    }

    @Test
    fun `a value quoted in the diagnostic itself is withheld too`() {
        // Not behind the JSON input marker: "Failed to parse literal '...'" quotes the value.
        val secret = "https://b.example/?token=quoted"
        val error = failureOf { json.decodeFromString<Page>("""{"url":"x","visitCount":"$secret"}""") }

        val fields = assertWithheld(secret, error)

        assertEquals("$.visitCount", fields["path"], "the path is structure and stays useful: $fields")
        assertEquals(25, fields["offset"])
    }

    @Test
    fun `a map key in the path is masked, because the zoom settings key by domain`() {
        val domain = "private-intranet.example"
        val error = failureOf { json.decodeFromString<Zoom>("""{"levels":{"$domain":"big"}}""") }

        val fields = assertWithheld(domain, error)

        assertEquals("$.levels[*]", fields["path"])
    }

    @Test
    fun `a map key with a space in it is masked even though it cuts the path short`() {
        // The path match stops at the space, leaving an unclosed `['intranetbank` to mask.
        val key = "intranetbank login.example"
        val error = failureOf { json.decodeFromString<Zoom>("""{"levels":{"$key":"big"}}""") }

        val fields = assertWithheld("intranetbank", error)

        assertEquals("$.levels[*]", fields["path"])
    }
}
