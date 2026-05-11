#!/usr/bin/env bash
# Bootstrap / republish the vocal-monitor-plugins registry to GitHub.
#
# First run: writes repo.config.json with your GitHub user, rebuilds the
# manifest so source URLs point at YOUR fork, initialises git, creates the
# remote repository (via gh CLI when available, otherwise prints manual
# instructions), and pushes the initial commit.
#
# Subsequent runs: rebuilds the manifest, commits any changes and pushes —
# safe to re-run after editing plugins.
#
# Usage:
#   ./publish.sh <github-username> [<repo-name>]
#
# Examples:
#   ./publish.sh Edza48
#   ./publish.sh Edza48 my-vocal-fx-pack

set -e

# ── Resolve target ────────────────────────────────────────────────────
USER="${1:-}"
REPO="${2:-vocal-monitor-plugins}"
if [[ -z "$USER" ]]; then
    echo "Usage: $0 <github-username> [<repo-name>]"
    exit 1
fi
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

REMOTE_URL="https://github.com/$USER/$REPO.git"
MANIFEST_URL="https://raw.githubusercontent.com/$USER/$REPO/main/manifest.json"

echo "→ Target: $USER/$REPO"
echo "→ Root:   $ROOT"

# ── 1. Pin repo.config.json to this user/repo ─────────────────────────
# build-manifest.mjs reads this file when GITHUB_REPOSITORY isn't in the
# environment (i.e. when running locally). Pinning it once means every
# subsequent local build bakes the right raw-URL prefix into manifest.json
# automatically.
cat > repo.config.json <<EOF
{
  "repository": "$USER/$REPO",
  "branch": "main"
}
EOF
echo "✓ Wrote repo.config.json"

# ── 2. Validate + (re)build manifest ──────────────────────────────────
if ! command -v node &>/dev/null; then
    echo "✗ node is required (v20+). Install from https://nodejs.org/ and re-run."
    exit 1
fi
node scripts/validate-plugins.mjs
node scripts/build-manifest.mjs

# ── 3. Init git repo (idempotent) ─────────────────────────────────────
if [[ ! -d .git ]]; then
    git init -b main
    echo "✓ git init"
else
    echo "• git repo already initialised"
fi

# ── 4. Stage + commit anything new ────────────────────────────────────
git add -A
if [[ -n "$(git status --porcelain)" ]]; then
    if git rev-parse HEAD &>/dev/null; then
        # Repo already has history — incremental commit.
        git commit -m "Publish: rebuild manifest for $USER/$REPO"
    else
        # First commit ever.
        git commit -m "Initial commit: 16 plugins across 6 categories

Bootstrapped via publish.sh. Each plugin lives under plugins/<category>/<id>/
with a plugin.json metadata file and an <id>.js source. CI validates every
PR; pushes to main automatically rebuild manifest.json. The app fetches
this manifest URL:

  $MANIFEST_URL
"
    fi
    echo "✓ Committed"
else
    echo "• Nothing to commit"
fi

# ── 5. Configure 'origin' remote ──────────────────────────────────────
if ! git remote get-url origin &>/dev/null; then
    git remote add origin "$REMOTE_URL"
    echo "✓ Added remote origin → $REMOTE_URL"
else
    EXISTING="$(git remote get-url origin)"
    if [[ "$EXISTING" != "$REMOTE_URL" ]]; then
        echo "! origin currently points to: $EXISTING"
        echo "! Run \`git remote set-url origin $REMOTE_URL\` to repoint, or pick a different repo name."
        exit 1
    fi
    echo "• origin already set"
fi

# ── 6. Create the GitHub repo (if gh available) and push ──────────────
if command -v gh &>/dev/null; then
    if gh repo view "$USER/$REPO" &>/dev/null; then
        echo "• GitHub repo $USER/$REPO already exists"
    else
        echo "→ Creating public GitHub repo $USER/$REPO …"
        gh repo create "$USER/$REPO" --public --source=. --remote=origin --description "Community JS audio plugins for the Vocal Monitor Android app" || {
            echo "✗ gh repo create failed. You may need to run \`gh auth login\` first."
            exit 1
        }
    fi
    git push -u origin main
    echo "✓ Pushed to $REMOTE_URL"
else
    cat <<EOF

──────────────────────────────────────────────────────────────────────
gh CLI not installed — manual step required to create the GitHub repo:

  1. Open https://github.com/new
  2. Repository name: $REPO
  3. Visibility: PUBLIC
  4. Don't tick "Initialize with README / .gitignore / license"
     (we already have all three)
  5. Create.
  6. Come back here and run:

       git push -u origin main

  (Optional) Install gh CLI to skip this step next time:
       winget install --id GitHub.cli
       gh auth login

──────────────────────────────────────────────────────────────────────
EOF
fi

# ── 7. Done — show the URL the app needs ──────────────────────────────
cat <<EOF

──────────────────────────────────────────────────────────────────────
DONE.

Paste this URL into the app:
  Voice Effects → 📦 Plugin library → Registry URL:

  $MANIFEST_URL

Tap the refresh button — every plugin in the registry appears in the
catalogue with an "Online" badge.
──────────────────────────────────────────────────────────────────────
EOF
