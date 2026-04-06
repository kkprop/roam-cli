#!/usr/bin/env bash
set -euo pipefail

REPO="kkprop/roam-cli"

# Detect OS + arch
OS=$(uname -s | tr '[:upper:]' '[:lower:]')
ARCH=$(uname -m)
case "$ARCH" in
  x86_64)  ARCH="x64" ;;
  aarch64) ARCH="arm64" ;;
  arm64)   ARCH="arm64" ;;
  *)       echo "❌ Unsupported architecture: $ARCH"; exit 1 ;;
esac
case "$OS" in
  darwin) OS="macos" ;;
  linux)  OS="linux" ;;
  *)      echo "❌ Unsupported OS: $OS"; exit 1 ;;
esac

ASSET="roam-cli-${OS}-${ARCH}.tar.gz"
URL="https://github.com/${REPO}/releases/latest/download/${ASSET}"
TMP=$(mktemp -d)

echo "📦 Downloading roam-cli (${OS}-${ARCH})..."
if ! curl -sL --fail -o "${TMP}/${ASSET}" "$URL"; then
  echo "❌ Download failed. Check https://github.com/${REPO}/releases"
  rm -rf "$TMP"
  exit 1
fi

echo "📂 Extracting..."
tar xzf "${TMP}/${ASSET}" -C "$TMP"
chmod +x "${TMP}/roam-cli"

# Clear macOS Gatekeeper quarantine
if [ "$OS" = "macos" ]; then
  xattr -d com.apple.quarantine "${TMP}/roam-cli" 2>/dev/null || true
fi

# Install to PATH
DEST=""
if [ -w /usr/local/bin ]; then
  DEST="/usr/local/bin/roam-cli"
  mv "${TMP}/roam-cli" "$DEST"
elif command -v sudo &>/dev/null; then
  DEST="/usr/local/bin/roam-cli"
  echo "🔑 Need sudo to install to /usr/local/bin/"
  sudo mv "${TMP}/roam-cli" "$DEST"
  if [ "$OS" = "macos" ]; then
    sudo xattr -d com.apple.quarantine "$DEST" 2>/dev/null || true
  fi
else
  mkdir -p "$HOME/bin"
  DEST="$HOME/bin/roam-cli"
  mv "${TMP}/roam-cli" "$DEST"
  echo "ℹ️  Installed to ~/bin/roam-cli — make sure ~/bin is in your PATH"
fi

rm -rf "$TMP"

# macOS security note
if [ "$OS" = "macos" ]; then
  echo ""
  echo "⚠️  macOS may block this app on first run."
  echo "   If you see 'killed' or a security warning:"
  echo "   → Open System Settings > Privacy & Security"
  echo "   → Click 'Allow Anyway' next to roam-cli"
  echo "   → Run roam-cli again"
  echo ""
fi

# Verify
if "$DEST" --version 2>/dev/null; then
  echo "✅ roam-cli installed to ${DEST}"
  echo "   Run: roam-cli setup"
else
  echo "✅ Installed to ${DEST}"
  echo "   Run: roam-cli setup"
fi
