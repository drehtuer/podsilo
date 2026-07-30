// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.naming

/** One piece of a tokenized folder/file template. */
sealed interface TemplateToken {
    data class Literal(
        val text: String,
    ) : TemplateToken

    data class Variable(
        val name: String,
        val pattern: String?,
    ) : TemplateToken
}

/**
 * `{podcast}`, `{title}`, `{date}` (optionally `{date:pattern}`), `{guid_short}`, and `{description}`
 * (CLAUDE.md section 6). `{ext}` is deliberately not resolved here: the extension is always
 * appended by the caller from [net.drehtuer.podsilo.core.model.port.ResolvedName.extension]
 * ("templates need not include it"), so a literal `{ext}` in a template is left untouched rather
 * than double-resolved.
 */
private val KNOWN_VARIABLES = setOf("podcast", "title", "description", "date", "guid_short")

private val TOKEN_PATTERN = Regex("""\{(\w+)(?::([^}]*))?}""")

/** Splits [template] into literal text runs and recognised `{variable}` tokens, in order. */
fun tokenizeTemplate(template: String): List<TemplateToken> {
    val tokens = mutableListOf<TemplateToken>()
    var consumedUpTo = 0

    for (match in TOKEN_PATTERN.findAll(template)) {
        if (match.range.first > consumedUpTo) {
            tokens += TemplateToken.Literal(template.substring(consumedUpTo, match.range.first))
        }
        val name = match.groupValues[1]
        tokens +=
            if (name in KNOWN_VARIABLES) {
                TemplateToken.Variable(name, match.groups[2]?.value)
            } else {
                TemplateToken.Literal(match.value)
            }
        consumedUpTo = match.range.last + 1
    }
    if (consumedUpTo < template.length) {
        tokens += TemplateToken.Literal(template.substring(consumedUpTo))
    }
    return tokens
}
