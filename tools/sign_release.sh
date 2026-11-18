#!/usr/bin/env bash
# Signs a FluxTV release APK with the release key and the key lineage in signing/fluxtv.lineage,
# so that it installs as an update over every FluxTV already on a TV, whichever key signed it.
#
#   FLUXTV_KEYSTORE=/path/fluxtv-release.p12 FLUXTV_KEYSTORE_PASSWORD=… \
#     tools/sign_release.sh app/build/outputs/apk/release/app-release.apk
#
# The lineage (APK Signature Scheme v3 key rotation, Android 9 and newer) is a chain of keys, each
# step signed by the key before it: this machine's debug key (7a3d2f79…, the MiTV and the MiniX),
# then the T480S debug key (6231ec4d…, the Raspberry Pis), then the release key (b3e0662d…). Android
# accepts an APK signed with the release key as an update to an app signed with any key earlier in
# the chain, and keeps its data. The lineage holds certificates only, no private keys.
#
# --rotation-min-sdk-version 28 is what applies the rotation from Android 9: apksigner otherwise
# applies it from Android 13 only, and older TVs would refuse the update.
#
# Only the v3 signature is written. The older v1 and v2 schemes cannot carry a lineage, so apksigner
# would sign them with the oldest key in it, a debug key that has no business in a release. Android
# 7 and 8 read only those schemes, so they cannot install a release: the Release workflow says so in
# update.json (minSdk 28), and a FluxTV there is not offered one.
#
# The APK is signed in place. Gradle may have signed it already; apksigner replaces that signature.
# FLUXTV_KEY_ALIAS defaults to fluxtv-release, FLUXTV_KEY_PASSWORD to the keystore password.
set -euo pipefail

apk="${1:?usage: sign_release.sh APK}"
here="$(cd "$(dirname "$0")/.." && pwd)"
lineage="$here/signing/fluxtv.lineage"
: "${FLUXTV_KEYSTORE:?set FLUXTV_KEYSTORE to the release keystore}"
: "${FLUXTV_KEYSTORE_PASSWORD:?set FLUXTV_KEYSTORE_PASSWORD}"
export FLUXTV_KEY_PASSWORD="${FLUXTV_KEY_PASSWORD:-$FLUXTV_KEYSTORE_PASSWORD}"

if [ -z "${APKSIGNER:-}" ]; then
  sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  [ -n "$sdk" ] || { echo "Set ANDROID_HOME or APKSIGNER" >&2; exit 1; }
  tools="$(ls -d "$sdk"/build-tools/*/ | sort -V | tail -1)"
  APKSIGNER="${tools}apksigner"
  [ -e "$APKSIGNER" ] || APKSIGNER="${tools}apksigner.bat"
fi

"$APKSIGNER" sign \
  --ks "$FLUXTV_KEYSTORE" \
  --ks-key-alias "${FLUXTV_KEY_ALIAS:-fluxtv-release}" \
  --ks-pass env:FLUXTV_KEYSTORE_PASSWORD \
  --key-pass env:FLUXTV_KEY_PASSWORD \
  --lineage "$lineage" \
  --rotation-min-sdk-version 28 \
  --v1-signing-enabled false \
  --v2-signing-enabled false \
  "$apk"

"$APKSIGNER" verify --verbose --print-certs "$apk" | grep -E "Verified using|certificate SHA-256" || true
