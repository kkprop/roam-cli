#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

REPO="kkprop/roam-cli"
TOKEN=$(cat ~/.config/mata/github-token)

# 1. Bump patch version
OLD_VER=$(cat VERSION | tr -d '\n')
IFS='.' read -r MAJOR MINOR PATCH <<< "$OLD_VER"
NEW_VER="$MAJOR.$MINOR.$((PATCH + 1))"
TAG="v$NEW_VER"
echo "$NEW_VER" > VERSION
echo "📦 $OLD_VER → $NEW_VER"

# 2. Git commit + push
git add -A
git commit -m "release: $TAG"
git push origin main

# 3. Git tag + push
git tag "$TAG"
git push origin "$TAG"

# 4. Build binary
bb build

# 5. Compress
tar czf dist/roam-macos-arm64.tar.gz -C dist roam
SIZE=$(du -h dist/roam-macos-arm64.tar.gz | cut -f1)
echo "📦 dist/roam-macos-arm64.tar.gz ($SIZE)"

# 6. Create GitHub release, parse upload URL
RELEASE_JSON=$(curl -s -X POST \
  "https://api.github.com/repos/$REPO/releases" \
  -H "Authorization: token $TOKEN" \
  -H "Content-Type: application/json" \
  -d "$(bb -e '(cheshire.core/generate-string {:tag_name "'"$TAG"'" :name "'"$TAG"'" :body "roam-cli '"$NEW_VER"'"})')")

UPLOAD_URL=$(echo "$RELEASE_JSON" | bb -e '(-> (cheshire.core/parse-string (slurp *in*) true) :upload_url (clojure.string/replace "{?name,label}" ""))')
HTML_URL=$(echo "$RELEASE_JSON" | bb -e '(-> (cheshire.core/parse-string (slurp *in*) true) :html_url)')
RELEASE_ID=$(echo "$RELEASE_JSON" | bb -e '(-> (cheshire.core/parse-string (slurp *in*) true) :id)')

if [ -z "$RELEASE_ID" ] || [ "$RELEASE_ID" = "null" ]; then
  echo "❌ Failed to create release"
  echo "$RELEASE_JSON"
  exit 1
fi

# 7. Upload asset
echo "📤 Uploading binary..."
curl -s -X POST \
  "${UPLOAD_URL}?name=roam-macos-arm64.tar.gz" \
  -H "Authorization: token $TOKEN" \
  -H "Content-Type: application/gzip" \
  --data-binary @dist/roam-macos-arm64.tar.gz > /dev/null

# 8. Done
echo "✅ Released $TAG → $HTML_URL"
echo "   Asset: roam-macos-arm64.tar.gz ($SIZE)"
