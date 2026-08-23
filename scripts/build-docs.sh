#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Builds the documentation site into build/site/.
#
# This exists so that the local build and the CI build (.github/workflows/docs.yml)
# are the same command, because the site is no longer just `antora` — one page is
# generated first. Run `antora` on its own and the security page is missing, which
# fails the build on an unresolved xref rather than quietly shipping a hole.
#
# THE GENERATED PAGE: the security policy has to be Markdown at the repository root,
# because that filename is what GitHub's Security tab and its "Report a vulnerability"
# flow read. It should also be readable on the site. Copying it would leave two
# policies to keep in step — the one thing a security policy must never have — so
# SECURITY.md stays the single source and kramdoc renders it into the component at
# build time. The result is gitignored and never committed: a generated file in git
# is a file that drifts.
#
# Kroki must be up for the Mermaid diagrams:
#   cd .devcontainer && docker compose up -d kroki
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

readonly source_md='SECURITY.md'
readonly generated_adoc='docs/modules/ROOT/pages/security.adoc'

tmp="$(mktemp)"
trap 'rm -f "${tmp}"' EXIT

# --format=GFM because that is what GitHub renders it as; --wrap=preserve keeps the
# line breaks so the generated file diffs sanely when something goes wrong with it.
kramdoc --format=GFM --wrap=preserve -o "${tmp}" "${source_md}"

# A banner rather than a bare copy: this file sits among hand-written pages, and the
# next person to open it will otherwise edit it and lose the change on the next build.
{
    echo "// GENERATED FILE — do not edit."
    echo "// Rendered from ${source_md} by scripts/build-docs.sh. Edit that file instead."
    cat "${tmp}"
} > "${generated_adoc}"

# --log-failure-level=warn is what makes the build a check: Antora reports an
# unresolved xref, a page missing from the nav, or a diagram Kroki refused as a
# *warning* and still exits 0, which would publish a site with holes in it.
exec antora --fetch --log-failure-level=warn antora-playbook.yml "$@"
