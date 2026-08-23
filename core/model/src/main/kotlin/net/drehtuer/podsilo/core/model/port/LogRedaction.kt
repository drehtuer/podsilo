// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.model.port

/** What replaces a secret. Deliberately visible: a redacted log should look redacted. */
private const val REDACTED = "<redacted>"

/**
 * Strips the shapes a credential arrives in from free text on its way into the error log
 * (`docs/UI.adoc` §11: *"Never contains the app password, the Basic-auth header, or full URLs with
 * credentials"*).
 *
 * **This is a net, not a guarantee, and the difference matters.** It removes credentials that carry
 * a marker — an `Authorization:` header, a `Basic`/`Bearer` token, `user:pass@host`, a
 * `?password=` parameter — because that is how they turn up: inside an exception message nobody
 * wrote by hand. It cannot recognise a bare app password sitting in a sentence, because such a
 * string is indistinguishable from any other. The actual guarantee is that no call site passes one,
 * and this function is the second line of defence for the ones that arrive by accident.
 *
 * Applied by the store rather than by callers ([LogRepository.record]), so a new write point cannot
 * forget it.
 *
 * Only *free text* — [NewLogEntry.message] and [NewLogEntry.detail] — is redacted.
 * [NewLogEntry.feedUrl] and [NewLogEntry.episodeKey] are identifiers the UI navigates by, and a feed
 * URL is already shown on S1 as the title fallback: redacting it here alone would be theatre that
 * broke the *tap an entry to reach the episode* affordance for nothing.
 */
fun redactSecrets(text: String): String =
    text
        .replace(AUTHORIZATION_HEADER) { "${it.groupValues[1]}$REDACTED" }
        .replace(AUTH_SCHEME_TOKEN) { "${it.groupValues[1]} $REDACTED" }
        .replace(URL_USERINFO) { it.groupValues[1] }
        .replace(SECRET_QUERY_PARAM) { "${it.groupValues[1]}$REDACTED" }

/** [redactSecrets] applied to the free-text fields. */
fun NewLogEntry.redacted(): NewLogEntry =
    copy(
        message = redactSecrets(message),
        detail = detail?.let(::redactSecrets),
    )

/**
 * `Authorization: Basic dXNlcjpwdw==`, however it was spelled or delimited.
 *
 * The scheme word has to be consumed *with* the token: a `\S+` stops at the space after `Basic` and
 * leaves the credential sitting in the log behind a `<redacted>` that looks like it worked. Caught
 * by the test rather than by review, which is the argument for having written the table first.
 */
private val AUTHORIZATION_HEADER =
    Regex("(?i)(authorization\\s*[:=]\\s*)(?:(?:basic|bearer|digest)\\s+)?\\S+")

/**
 * A bare `Basic <token>` / `Bearer <token>` with no header name in front of it — OkHttp prints
 * headers this way. The 8-character floor keeps prose like "Basic auth failed" intact, which
 * matters because that sentence is one a user is meant to read.
 */
private val AUTH_SCHEME_TOKEN = Regex("(?i)\\b(basic|bearer)\\s+([A-Za-z0-9+/=._~-]{8,})")

/** `https://user:pw@host` — the userinfo cannot cross a `/`, so a path containing `@` is left alone. */
private val URL_USERINFO = Regex("([a-zA-Z][a-zA-Z0-9+.\\-]*://)[^/\\s@]+@")

/** `?token=…`, `&password=…` — private feeds and enclosure URLs really do carry these. */
private val SECRET_QUERY_PARAM =
    Regex("(?i)([?&](?:app_?password|password|passwd|secret|token|auth|api_?key)=)[^&\\s]*")
