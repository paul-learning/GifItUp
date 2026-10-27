package app.gifitup.shared.data.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContentCodecTest {
    private val url = "https://static.klipy.com/ii/0123456789abcdef0123456789abcdef/ab/cd/funny_cat-1.webp"

    private fun json(
        v: String = "1",
        type: String = "\"gif\"",
        rest: String = gifFields(),
    ) = """{"v":$v,"type":$type$rest}"""

    @Suppress("LongParameterList") // Builds test inputs: one parameter per field of the message.
    private fun gifFields(
        provider: String = "klipy",
        slug: String = "funny-cat",
        url: String = this.url,
        width: String = "320",
        height: String = "240",
        caption: String? = null,
    ) = ""","provider":"$provider","slug":"$slug","url":"$url","width":$width,"height":$height""" +
        (caption?.let { ""","caption":$it""" } ?: "")

    private fun decode(text: String) = ContentCodec.decode(text.encodeToByteArray())

    private fun gif(text: String): Content.Gif {
        val message = assertIs<Decoded.Message>(decode(text))
        return assertIs<Content.Gif>(message.content)
    }

    @Test
    fun aValidGifIsDecoded() {
        val decoded = gif(json())
        assertEquals("klipy", decoded.reference.provider)
        assertEquals("funny-cat", decoded.reference.slug)
        assertEquals(url, decoded.reference.url)
        assertEquals(320, decoded.reference.width)
        assertEquals(240, decoded.reference.height)
        assertNull(decoded.caption)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        gif(json(rest = gifFields() + ""","extra":{"a":[1,2.5e3,true,null,"x"],"b":-0}"""))
    }

    @Test
    fun badUrlsAreInvalid() {
        val hex = "0123456789abcdef0123456789abcdef"
        val bad =
            listOf(
                "https://static.klipy.com.evil.test/ii/$hex/ab/cd/a.webp" to "lookalike host",
                "https://xstatic.klipy.com/ii/$hex/ab/cd/a.webp" to "prefixed host",
                "https://static3.klipy.com/ii/$hex/ab/cd/a.webp" to "unknown static host",
                "https://static.klipy.co/ii/$hex/ab/cd/a.webp" to "other tld",
                "https://STATIC.klipy.com/ii/$hex/ab/cd/a.webp" to "uppercase host",
                "HTTPS://static.klipy.com/ii/$hex/ab/cd/a.webp" to "uppercase scheme",
                "https://static.klipy.com/ii/${hex.uppercase()}/ab/cd/a.webp" to "uppercase hex",
                "https://static.klipy.com/ii/$hex/ab/cd/a.WEBP" to "uppercase extension",
                "https://static.klipy.com:443/ii/$hex/ab/cd/a.webp" to "port",
                "https://static.klipy.com/ii/$hex/ab/cd/a.webp?x=1" to "query",
                "https://static.klipy.com/ii/$hex/ab/cd/a.webp#f" to "fragment",
                "https://user@static.klipy.com/ii/$hex/ab/cd/a.webp" to "userinfo",
                "https://static.klipy.com@evil.test/ii/$hex/ab/cd/a.webp" to "userinfo host trick",
                "http://static.klipy.com/ii/$hex/ab/cd/a.webp" to "http",
                "https://static.klipy.com/ii/$hex/ab/cd/a%2Ewebp" to "percent-encoded dot",
                "https://static.klipy.com/ii/$hex/ab/cd/a%5Fb.webp" to "percent-encoded name",
                "https://static.klipy.com/ii/$hex/ab/cd/a.webp\n" to "trailing newline",
                "\nhttps://static.klipy.com/ii/$hex/ab/cd/a.webp" to "leading newline",
                " https://static.klipy.com/ii/$hex/ab/cd/a.webp" to "leading space",
                "https://static.klipy.com/ii/$hex/ab/cd/a.png" to "other extension",
                "https://static.klipy.com/ii/$hex/ab/cd/${"a".repeat(65)}.webp" to "name too long",
                "https://static.klipy.com/ii/${hex.drop(1)}/ab/cd/a.webp" to "short hex",
                "https://static.klipy.com/ii/$hex/ab/cd/../a.webp" to "dot segments",
                "https://static.klipy.com/ii/$hex/ab/cd/a.webp/" to "trailing slash",
                "https://static.klipy.com\\ii/$hex/ab/cd/a.webp" to "backslash",
                "https://static.klipy.com/ii/$hex/ab/cd/" to "no name",
                "" to "empty",
            )
        for ((candidate, what) in bad) {
            assertFalse(GifReference.validUrl(candidate), what)
            assertEquals(Decoded.Invalid, decode(json(rest = gifFields(url = candidate.replace("\\", "\\\\").replace("\n", "\\n")))), what)
        }
        for (host in listOf("static", "static1", "static2")) {
            for (extension in listOf("gif", "webp")) {
                assertTrue(GifReference.validUrl("https://$host.klipy.com/ii/$hex/ab/cd/${"n".repeat(64)}.$extension"), "$host $extension")
            }
        }
    }

    @Test
    fun badGifFieldsAreInvalid() {
        val bad =
            listOf(
                gifFields(provider = "giphy"),
                gifFields(provider = "Klipy"),
                gifFields(provider = ""),
                gifFields(slug = ""),
                gifFields(slug = "a".repeat(129)),
                gifFields(slug = "has space"),
                gifFields(slug = "caf\\u00e9"),
                gifFields(width = "0"),
                gifFields(width = "2049"),
                gifFields(height = "-1"),
                gifFields(height = "0"),
                gifFields(width = "320.0"),
                gifFields(width = "1e2"),
                gifFields(width = "\"320\""),
                gifFields(width = "null"),
                gifFields(width = "99999999999"),
                gifFields(width = "01"),
            )
        for (fields in bad) assertEquals(Decoded.Invalid, decode(json(rest = fields)), fields)
        val missing = listOf("provider", "slug", "url", "width", "height")
        for (name in missing) {
            val without = gifFields().split(",\"").filterNot { it.startsWith("$name\"") }.joinToString(",\"")
            assertEquals(Decoded.Invalid, decode(json(rest = without)), name)
        }
        assertEquals(Decoded.Invalid, decode(json(rest = gifFields().replace("\"slug\":\"funny-cat\"", "\"slug\":5"))))
        assertEquals(Decoded.Invalid, decode(json(rest = gifFields().replace("\"slug\":\"funny-cat\"", "\"slug\":null"))))
    }

    @Test
    fun sizeHintsAtTheLimitsAreAccepted() {
        assertEquals(1, gif(json(rest = gifFields(width = "1", height = "2048"))).reference.width)
        assertEquals(2048, gif(json(rest = gifFields(width = "1", height = "2048"))).reference.height)
    }

    @Test
    fun captionRules() {
        assertNull(gif(json(rest = gifFields(caption = "null"))).caption)
        assertNull(gif(json(rest = gifFields(caption = "\"\""))).caption)
        assertNull(gif(json(rest = gifFields(caption = "\"   \""))).caption)
        assertEquals("hi", gif(json(rest = gifFields(caption = "\" hi \""))).caption)
        assertEquals("a\"b\\c", gif(json(rest = gifFields(caption = "\"a\\\"b\\\\c\""))).caption)
        assertEquals("x".repeat(120), gif(json(rest = gifFields(caption = "\"${"x".repeat(120)}\""))).caption)
        val bad = listOf("5", "true", "[]", "{}", "\"hi\\n\"", "\"\\u3164\"", "\"\\u202e\"", "\"${"x".repeat(121)}\"", "\"\\ud800\"")
        for (caption in bad) {
            assertEquals(Decoded.Invalid, decode(json(rest = gifFields(caption = caption))), caption)
        }
        val emoji = "\uD83D\uDE00"
        assertEquals(emoji.repeat(120), gif(json(rest = gifFields(caption = "\"${emoji.repeat(120)}\""))).caption)
    }

    @Test
    fun groupMetaRules() {
        val meta = assertIs<Decoded.Message>(decode("""{"v":1,"type":"group_meta","name":" Friends "}""")).content
        assertEquals("Friends", assertIs<Content.GroupMeta>(meta).name)
        for (name in listOf("\"\"", "\"  \"", "\"\\u3164\"", "\"a\\nb\"", "\"${"x".repeat(65)}\"", "5", "null")) {
            assertEquals(Decoded.Invalid, decode("""{"v":1,"type":"group_meta","name":$name}"""), name)
        }
        assertEquals(Decoded.Invalid, decode("""{"v":1,"type":"group_meta"}"""))
        assertIs<Decoded.Message>(decode("""{"v":1,"type":"group_meta","name":"${"x".repeat(64)}"}"""))
    }

    @Test
    fun versionRules() {
        assertEquals(Decoded.Unsupported, decode(json(v = "2")), "a known type at v=2 is never rendered")
        assertEquals(Decoded.Unsupported, decode(json(v = "2", type = "\"future\"", rest = "")))
        assertEquals(Decoded.Unsupported, decode(json(type = "\"future\"", rest = "")))
        assertEquals(Decoded.Unsupported, decode(json(type = "\"\"", rest = "")))
        assertEquals(Decoded.Invalid, decode(json(v = "0")))
        assertEquals(Decoded.Invalid, decode(json(v = "-1")))
        assertEquals(Decoded.Invalid, decode(json(v = "0", type = "\"future\"", rest = "")))
        for (v in listOf("\"1\"", "1.0", "1e0", "null", "true", "[]", "{}", "99999999999")) {
            assertEquals(Decoded.Invalid, decode(json(v = v)), v)
        }
        assertEquals(Decoded.Invalid, decode("""{"type":"gif"${gifFields()}}"""), "v is required")
    }

    @Test
    fun missingOrNonStringTypesAreInvalidNotUnsupported() {
        for (type in listOf("null", "1", "{}", "[]", "true", "\"\\ud800\"x")) {
            assertEquals(Decoded.Invalid, decode("""{"v":1,"type":$type}"""), type)
        }
        assertEquals(Decoded.Invalid, decode("""{"v":1}"""))
        assertEquals(Decoded.Invalid, decode("""{"v":2}"""))
    }

    @Test
    fun annotationsAreDroppedNeverShownOrInvalid() {
        val target = "A".repeat(42) + "A"
        assertEquals(Decoded.Dropped, decode("""{"v":1,"type":"ann_reaction","target":"$target","reaction":"x"}"""))
        assertEquals(Decoded.Dropped, decode("""{"v":2,"type":"ann_reaction","target":"$target"}"""))
        assertEquals(Decoded.Dropped, decode("""{"v":0,"type":"ann_reaction"}"""))
        assertEquals(Decoded.Dropped, decode("""{"type":"ann_reaction"}"""))
        assertEquals(Decoded.Dropped, decode("""{"v":"x","type":"ann_"}"""))
        assertEquals(Decoded.Dropped, decode("""{"v":1,"type":"ann_unknown_thing","target":5}"""))
        assertEquals(Decoded.Unsupported, decode("""{"v":1,"type":"unknown_thing"}"""), "no ann_ prefix: still a placeholder")
        assertEquals(Decoded.Unsupported, decode("""{"v":1,"type":"Ann_x"}"""), "the prefix is case-sensitive")
        assertEquals(Decoded.Invalid, decode("""{"v":1,"type":"ann_reaction""""), "a broken envelope has no readable type")
        assertEquals(Decoded.Invalid, decode("""{"v":1,"type":null}"""))
    }

    @Test
    fun duplicateKeysAnywhereAreInvalid() {
        val cases =
            listOf(
                """{"v":1,"type":"gif","type":"group_meta","name":"x"}""",
                """{"v":1,"type":"group_meta","name":"x","\u006eame":"y"}""",
                """{"v":1,"v":2,"type":"future"}""",
                """{"v":1,"type":"group_meta","name":"x","extra":{"a":1,"a":2}}""",
                """{"v":1,"type":"ann_reaction","target":"a","target":"b"}""",
                """{"v":1,"type":"future","list":[{"k":1,"k":1}]}""",
            )
        for (case in cases) assertEquals(Decoded.Invalid, decode(case), case)
    }

    @Test
    fun malformedJsonIsInvalid() {
        val cases =
            listOf(
                "",
                " ",
                "{",
                "}",
                "[]",
                "\"x\"",
                "1",
                "null",
                "{}",
                """{"v":1,"type":"gif"} x""",
                """{"v":1,"type":"gif"},""",
                """{"v":1,"type":"gif",}""",
                """{"v":1 "type":"gif"}""",
                """{v:1,"type":"gif"}""",
                """{'v':1}""",
                """{"v":1,"type":"gif"/*c*/}""",
                """{"v":NaN,"type":"gif"}""",
                """{"v":01,"type":"gif"}""",
                """{"v":+1}""",
                "{\"v\":1,\"type\":\"gi\u0001f\"}",
                "{\"v\":1,\"type\":\"gi\nf\"}",
                """{"v":1,"type":"g\xf"}""",
                """{"v":1,"type":"\u00zz"}""",
                """{"v":1,"type":"ab""",
                """{"v":1,"x":tru}""",
                """{"v":1,"x":nul}""",
                """{"v":1,"x":1.}""",
                """{"v":1,"x":.5}""",
                """{"v":1,"x":1e}""",
                """{"v":1,"x":-}""",
                """{"v":1,"x":[1,]}""",
                """{"v":1,"x":[}""",
            )
        for (case in cases) assertEquals(Decoded.Invalid, decode(case), case)
        val deep = "[".repeat(20) + "]".repeat(20)
        assertEquals(Decoded.Invalid, decode("""{"v":1,"type":"future","x":$deep}"""))
    }

    @Test
    fun invalidUtf8IsInvalid() {
        val valid = json().encodeToByteArray()
        assertIs<Decoded.Message>(ContentCodec.decode(valid))
        val broken = valid.copyOf()
        broken[broken.indexOf('f'.code.toByte())] = 0xC3.toByte() // a lone lead byte inside "gif"
        assertEquals(Decoded.Invalid, ContentCodec.decode(broken))
        assertEquals(Decoded.Invalid, ContentCodec.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte())))
        assertEquals(Decoded.Invalid, ContentCodec.decode(byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte())), "encoded surrogate")
        assertEquals(Decoded.Invalid, ContentCodec.decode(ByteArray(0)))
    }

    @Test
    fun theSizeCapIsCheckedBeforeParsing() {
        val base = json().length
        val atLimit = json(rest = gifFields() + ""","pad":"${"x".repeat(ContentCodec.MAX_BYTES - base - 9)}"""")
        assertEquals(ContentCodec.MAX_BYTES, atLimit.encodeToByteArray().size)
        assertIs<Decoded.Message>(decode(atLimit))
        assertEquals(Decoded.Invalid, decode(atLimit.replace("pad\":\"", "pad\":\"x")))
        assertEquals(Decoded.Invalid, ContentCodec.decode(ByteArray(100_000) { '{'.code.toByte() }))
    }

    @Test
    fun failuresCarryNoInputText() {
        val marker = "SECRET-MARKER-9f3"
        val inputs =
            listOf(
                """{"v":1,"type":"$marker"}""",
                """{"v":2,"type":"gif","x":"$marker"}""",
                """{"v":1,"type":"gif","slug":"$marker"}""",
                """{"v":1,"type":"ann_$marker"}""",
                """{"v":1,"type":"gif","caption":"$marker\n"}""",
                "{$marker}",
                """{"v":1,"type":"group_meta","name":"$marker","name":"$marker"}""",
            )
        for (input in inputs) {
            val result = decode(input)
            assertFalse(result.toString().contains(marker), input)
            assertFalse(result is Decoded.Message, input)
        }
    }

    @Test
    fun theTwoParsersAgreeOnStrings() {
        val text = """{"s":"a\u00e9\ud83d\ude00\n\/\"\\\b\f\r\t","k\u0065y":"v"}"""
        val ours = assertIs<JsonValue.Obj>(StrictJson.parse(text))
        val theirs = ContentJson.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        assertEquals((theirs["s"] as kotlinx.serialization.json.JsonPrimitive).content, (ours.fields["s"] as JsonValue.Str).value)
        assertEquals(theirs.keys, ours.fields.keys)
    }

    @Test
    fun encodedGifsRoundTrip() {
        val reference = assertNotNull(GifReference.of("klipy", "funny-cat", url, 320, 240))
        val withCaption = assertNotNull(ContentCodec.encodeGif(reference, " hi "))
        val decoded = assertIs<Content.Gif>(assertIs<Decoded.Message>(ContentCodec.decode(withCaption)).content)
        assertEquals(reference, decoded.reference)
        assertEquals("hi", decoded.caption)
        for (none in listOf(null, "", "   ")) {
            val bytes = assertNotNull(ContentCodec.encodeGif(reference, none))
            assertTrue(bytes.decodeToString().endsWith(""","caption":null}"""), "a writer always sends the field")
            assertNull(assertIs<Content.Gif>(assertIs<Decoded.Message>(ContentCodec.decode(bytes)).content).caption)
        }
        assertNull(ContentCodec.encodeGif(reference, "x".repeat(121)))
        assertNull(ContentCodec.encodeGif(reference, "hi\n"))
        assertNull(ContentCodec.encodeGif(reference, "\u3164"))
    }

    @Test
    fun encodedGroupMetaRoundTrips() {
        val bytes = assertNotNull(ContentCodec.encodeGroupMeta(" Friends \"x\" "))
        assertEquals("Friends \"x\"", assertIs<Content.GroupMeta>(assertIs<Decoded.Message>(ContentCodec.decode(bytes)).content).name)
        assertNull(ContentCodec.encodeGroupMeta(""))
        assertNull(ContentCodec.encodeGroupMeta("x".repeat(65)))
        assertNull(ContentCodec.encodeGroupMeta("a\nb"))
    }

    @Test
    fun theLargestValidGifIsOneBlock() {
        val name = "n".repeat(64)
        val longestUrl = "https://static1.klipy.com/ii/${"f".repeat(32)}/ab/cd/$name.webp"
        assertEquals(137, longestUrl.length)
        val reference = assertNotNull(GifReference.of("klipy", "s".repeat(128), longestUrl, 2048, 2048))
        val emoji = "\uD83D\uDE00"
        assertEquals(365, assertNotNull(ContentCodec.encodeGif(reference, null)).size, "ADR-0006 B7: 365 with \"caption\":null")
        // 363 bytes with `"caption":""`, plus the caption's bytes: 4 x 120 = 480 for emoji, 2 x 120 for a quote or backslash.
        for ((caption, size) in listOf(emoji.repeat(120) to 843, "\"".repeat(120) to 603, "\\".repeat(120) to 603)) {
            val bytes = assertNotNull(ContentCodec.encodeGif(reference, caption))
            assertTrue(bytes.size <= ContentCodec.MAX_BYTES)
            assertEquals(size, bytes.size)
            assertEquals(caption, assertIs<Content.Gif>(assertIs<Decoded.Message>(ContentCodec.decode(bytes)).content).caption)
        }
    }
}
