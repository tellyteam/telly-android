package io.github.tellyteam.telly.data.update

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseParserTest {

    @Test
    fun `reads the version and the telly apk asset`() {
        val release = ReleaseParser.parse(
            Json.parseToJsonElement(
                """
                {"tag_name":"v1.2.0","draft":false,"prerelease":false,"body":"Faster Live TV.",
                 "assets":[{"name":"notes.txt","browser_download_url":"https://x/notes.txt"},
                           {"name":"telly.apk","browser_download_url":"https://x/telly.apk"}]}
                """.trimIndent(),
            ),
        )
        assertEquals(Release("1.2.0", "https://x/telly.apk", "Faster Live TV."), release)
    }

    @Test
    fun `ignores pre-releases and releases without the apk`() {
        assertNull(ReleaseParser.parse(Json.parseToJsonElement("""{"tag_name":"v2.0.0","prerelease":true,"assets":[{"name":"telly.apk","browser_download_url":"https://x"}]}""")))
        assertNull(ReleaseParser.parse(Json.parseToJsonElement("""{"tag_name":"v2.0.0","assets":[]}""")))
        assertNull(ReleaseParser.parse(Json.parseToJsonElement("""{"message":"Not Found"}""")))
    }

    @Test
    fun `compares versions numerically`() {
        assertTrue(ReleaseParser.isNewer("1.10.0", "1.9.3"))
        assertTrue(ReleaseParser.isNewer("v2.0", "1.99.99"))
        assertFalse(ReleaseParser.isNewer("1.0.0", "1.0.0"))
        assertFalse(ReleaseParser.isNewer("1.0.0", "1.0.1"))
        assertFalse(ReleaseParser.isNewer("1.0.0-beta", "1.0.0"))
    }

    @Test
    fun `release notes never show account names`() {
        val generated = """
            ## What's Changed
            * Live TV: play the focused channel by @someone in https://github.com/tellyteam/telly-android/pull/4
            * Thanks to @helper for testing

            ## New Contributors
            * @someone made their first contribution in https://github.com/tellyteam/telly-android/pull/1

            **Full Changelog**: https://github.com/tellyteam/telly-android/compare/v1.1.1...v1.2.0
        """.trimIndent()
        val clean = ReleaseParser.cleanNotes(generated)!!
        assertEquals("* Live TV: play the focused channel\n* Thanks to for testing", clean)
        assertFalse("@" in clean)
        assertEquals("- Faster Live TV.", ReleaseParser.cleanNotes("- Faster Live TV.\n"))
        assertNull(ReleaseParser.cleanNotes("**Full Changelog**: https://x"))
    }
}

class UpdateRulesTest {
    @Test
    fun `looked for at most daily`() {
        val now = 1_800_000_000_000L
        assertTrue(UpdateRules.due(0, now))
        assertFalse(UpdateRules.due(now - UpdateRules.DAY_MS + 1, now))
        assertTrue(UpdateRules.due(now - UpdateRules.DAY_MS, now))
    }
}
