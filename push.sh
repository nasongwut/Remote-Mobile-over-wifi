#!/usr/bin/env bash
# =============================================================================
#  Mobile Remote: commit all changes and push to the remote repository
#
#  Usage:
#    ./push.sh "Commit message"
#    ./push.sh                      uses a default message with the date
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")"

git rev-parse --is-inside-work-tree >/dev/null 2>&1 || { echo "Not a git repository. Run: git init"; exit 1; }
git remote get-url origin >/dev/null 2>&1 || { echo "No remote. Run: git remote add origin <url>"; exit 1; }

MSG="${1:-Update $(date '+%Y-%m-%d %H:%M')}"
BRANCH="$(git rev-parse --abbrev-ref HEAD)"

git add -A
if git diff --cached --quiet; then
  echo "Nothing to commit."
else
  git commit -m "$MSG"
fi

echo "==> Pushing $BRANCH to $(git remote get-url origin)"
git push -u origin "$BRANCH"
echo "==> Done"
