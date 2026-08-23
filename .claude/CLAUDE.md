# CLAUDE.md — Podsilo

The brief is AsciiDoc, like every other document in this repository: @CLAUDE.adoc

It should be expanded in full above or below this line. If it is not, read
`.claude/CLAUDE.adoc` before making any change — it carries the scope, the non-goals,
the mandated libraries, the testing requirements and the definition of done, and
section references written as "CLAUDE.md §4" mean §4 of that file.

<!--
Maintainer note (stripped before this file reaches Claude's context, so it costs no tokens):

The split exists because Claude Code only loads CLAUDE.md, while everything else here is
AsciiDoc and is linted by `asciidoctor --failure-level=WARN` in CI. Keeping the brief in
.adoc puts it under that check; keeping this stub satisfies the loader.

The import is `@CLAUDE.adoc`, resolved relative to THIS file's directory, so it means
.claude/CLAUDE.adoc. Do not wrap it in backticks — import parsing skips code spans, and
a backticked @path is deliberately treated as literal text rather than an import.

Verify with `/context` in a fresh session: the brief's own headings should appear under
the memory files, not just these few lines.
-->
