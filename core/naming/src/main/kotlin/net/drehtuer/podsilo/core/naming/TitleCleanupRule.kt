// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.naming

/**
 * One user-defined find/replace rule (CLAUDE.md section 6), e.g. stripping a `^Ep\.? ?\d+ *[-–—:] *`
 * prefix or a repeated show-name prefix. A plain ordered list, applied before sanitising -- no rule
 * engine, no built-in heuristics that would surprise the author by rewriting titles unasked.
 */
data class TitleCleanupRule(
    val pattern: Regex,
    val replacement: String,
)

fun applyCleanupRules(
    title: String,
    rules: List<TitleCleanupRule>,
): String = rules.fold(title) { current, rule -> rule.pattern.replace(current, rule.replacement) }
