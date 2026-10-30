#!/usr/bin/env bash
# Processes a custom image in the MRZ Reader app running on a connected device or emulator.
#
#   tools/process-image.sh path/to/passport.jpg            -> opens the MRZ result
#   tools/process-image.sh path/to/passport.jpg --redact   -> goes straight to the redaction screen
#
# The image is pushed to the app's own storage (no permission needed), then the app is started on it.
set -euo pipefail

if [[ $# -lt 1 || ! -f "$1" ]]; then
  echo "Usage: $0 <image file> [--redact]" >&2
  exit 1
fi

ADB="${ADB:-$(command -v adb || echo "$HOME/Library/Android/sdk/platform-tools/adb")}"
PKG="io.nutrient.poc.mrz"
DIR="/sdcard/Android/data/$PKG/files/inbox"
NAME="$(basename "$1" | tr ' ' '_')"
REDACT=false
[[ "${2:-}" == "--redact" ]] && REDACT=true

if [[ -z "$("$ADB" devices | sed -n '2p')" ]]; then
  echo "No device or emulator connected (adb devices is empty)." >&2
  exit 1
fi

"$ADB" shell mkdir -p "$DIR"
"$ADB" push "$1" "$DIR/$NAME" >/dev/null 2>&1
"$ADB" shell am start -n "$PKG/.MainActivity" --es path "$DIR/$NAME" --ez redact "$REDACT" >/dev/null
echo "Opened $NAME in MRZ Reader$([[ $REDACT == true ]] && echo " (redaction screen)")."
