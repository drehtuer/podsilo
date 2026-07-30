// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.feed

import java.nio.charset.Charset
import java.nio.charset.IllegalCharsetNameException
import java.nio.charset.UnsupportedCharsetException

private const val PROLOG_PREVIEW_BYTES = 256
private val ENCODING_DECLARATION =
    Regex(
        """<\?xml[^>]*\bencoding\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
private val PROLOG_ENCODING_ATTRIBUTE =
    Regex(
        """(<\?xml[^>]*\bencoding\s*=\s*["'])[^"']+(["'])""",
        RegexOption.IGNORE_CASE,
    )

/**
 * Decodes raw feed bytes to a [String], honouring the XML prolog's declared `encoding` rather than
 * assuming UTF-8 -- real feeds are sometimes declared and encoded as Latin-1/Windows-1252 (CLAUDE.md
 * section 7's "wrong encoding" fixture case). rssparser's `parse(String)` entry point takes an
 * already-decoded string, so this decision has to happen here, not inside the library.
 *
 * The prolog itself (`<?xml ... ?>`) is always pure ASCII regardless of the document's declared
 * encoding, so peeking at a byte-for-byte-stable prefix (ISO-8859-1: a 1:1 byte<->codepoint mapping
 * for all 256 byte values) to find the declaration is safe before committing to the full decode.
 *
 * The returned string's prolog is rewritten to declare `UTF-8`, no matter what it originally said.
 * This matters because rssparser's own `parse(String)` re-serialises the string to bytes as UTF-8
 * before re-parsing it (`AndroidXmlParser.generateParserInputFromString`) -- if the prolog text
 * still said e.g. `ISO-8859-1`, the library would decode its own freshly-UTF-8-encoded bytes as
 * ISO-8859-1 and mangle every non-ASCII character a second time.
 */
fun decodeFeedXml(bytes: ByteArray): String {
    val prologPreview = String(bytes, 0, minOf(bytes.size, PROLOG_PREVIEW_BYTES), Charsets.ISO_8859_1)
    val declaredEncoding = ENCODING_DECLARATION.find(prologPreview)?.groupValues?.get(1)
    val charset = declaredEncoding?.let(::charsetOrNull) ?: Charsets.UTF_8
    val decoded = String(bytes, charset)
    return PROLOG_ENCODING_ATTRIBUTE.replaceFirst(decoded, "$1UTF-8$2")
}

private fun charsetOrNull(name: String): Charset? =
    try {
        Charset.forName(name)
    } catch (
        @Suppress("SwallowedException") unrecognised: IllegalCharsetNameException,
    ) {
        null
    } catch (
        @Suppress("SwallowedException") unsupported: UnsupportedCharsetException,
    ) {
        null
    }
