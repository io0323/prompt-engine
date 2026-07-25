#!/bin/bash
# PostToolUse hook (Edit|Write tools, filtered to *.kt files via the "if" field
# in settings.json: "Edit(*.kt)" / "Write(*.kt)"). Reads the hook event JSON
# from stdin per https://code.claude.com/docs/en/hooks.
#
# NOTE: measured cold-start cost (Gradle daemon not warm) was 17-29s, well over
# the 5s budget for a per-edit hook. See CLAUDE.md / PR description for the
# recommendation to move this to a git pre-commit hook instead.
set -uo pipefail

cd "$CLAUDE_PROJECT_DIR" || exit 0
./gradlew ktlintFormat --quiet || true
exit 0
