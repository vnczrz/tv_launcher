#!/usr/bin/env bash
# Build Vox (tv.voxclean.launcher) and install it on the TV as Home, with upstream's launcher kept as the fallback.
#   TV_HOST=<tv ip> home/build-and-install.sh
# Needs JAVA_HOME (JDK 17) and ANDROID_HOME (SDK 34): a repo-root .toolchain/env.sh is sourced when present.
# Needs home/.env with the release keystore (VOX_KEYSTORE, VOX_KEYSTORE_PASSWORD, VOX_KEY_ALIAS, VOX_KEY_PASSWORD):
# one stable key, so a reinstall never wipes Vox's data. TOOLS=<dir> points at a tools/ with its venv and ADB key
# (default: ../tools).
#
# Never disables or uninstalls anything. Upstream's launcher (com.gathin.tvhome) stays installed: it is the fallback
# Home and its HomeKeyService runs the remote's YouTube and Netflix buttons. If Vox does not end up as Home, Home is
# set back to upstream and checked.
# Recovery by hand: tools/tv.py 'cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity', or on the
# couch: Settings (gear) > Android settings > Apps > Default home app.
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
export TV_HOST
TOOLS="$(cd "${TOOLS:-../tools}" && pwd)"
PY=$TOOLS/venv/bin/python
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
tv() { "$PY" -I "$TOOLS/tv.py" "$@"; }

VOX=tv.voxclean.launcher
VOX_HOME=$VOX/.MainActivity
UPSTREAM_HOME=com.gathin.tvhome/.MainActivity
KEY_SERVICE=com.gathin.tvhome/.HomeKeyService
HOME_QUERY='cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'

# The release key, checked before a minute of building: without it Gradle signs with the debug key, and the
# next install with the real key would be refused (or need an uninstall that wipes Vox's data).
[ -f .env ] || { echo "ERROR: home/.env is missing (the release keystore and its passwords); see this script's header." >&2; exit 1; }
STORE=$(sed -n 's/^VOX_KEYSTORE=//p' .env | head -n 1)
[ -n "$STORE" ] && [ -f "$STORE" ] || { echo "ERROR: VOX_KEYSTORE in home/.env does not name an existing keystore file." >&2; exit 1; }

MIN_SDK=$(sed -n 's/^[[:space:]]*minSdk[[:space:]][[:space:]]*\([0-9]\{1,3\}\)[[:space:]]*$/\1/p' app/build.gradle | head -n 1)
TV_SDK=$(tv 'getprop ro.build.version.sdk' 2>/dev/null | tr -dc '0-9' || true)
[ -n "$TV_SDK" ] || { echo "ERROR: the TV at TV_HOST does not answer over ADB." >&2; exit 1; }
if [ "$TV_SDK" -lt "$MIN_SDK" ]; then
  echo "ERROR: the TV runs SDK $TV_SDK and Vox needs $MIN_SDK. Nothing was built or installed." >&2; exit 1
fi
if [ -z "${JAVA_HOME:-}" ] && [ -f ../.toolchain/env.sh ]; then . ../.toolchain/env.sh; fi
: "${JAVA_HOME:?set JAVA_HOME to a JDK 17}"
: "${ANDROID_HOME:?set ANDROID_HOME to an Android SDK with platform 34}"
export JAVA_HOME ANDROID_HOME PATH="$JAVA_HOME/bin:$PATH"
./gradlew assembleRelease -q --console=plain
APK=app/build/outputs/apk/release/app-release.apk

# Upstream must be installed before Vox takes Home: it is the only way back.
case "$(tv 'pm list packages -e com.gathin.tvhome' 2>/dev/null | tr -d '\r')" in
  *package:com.gathin.tvhome*) ;;
  *) echo "ERROR: upstream's launcher (com.gathin.tvhome) is not installed and enabled, so there would be no fallback Home." >&2; exit 1 ;;
esac

"$PY" -I "$TOOLS/b64push.py" "$APK" /data/local/tmp/vox.apk
# Pushed and installed with pm: `adb install` hangs on Android 14.
OUT=$(tv 'pm install -r /data/local/tmp/vox.apk; rm -f /data/local/tmp/vox.apk')
echo "$OUT"
case "$OUT" in
  *Success*) ;;
  *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
    echo "ERROR: an earlier Vox signed with another key is installed. Make upstream Home first," >&2
    echo "  tools/tv.py 'cmd package set-home-activity --user 0 $UPSTREAM_HOME', then remove that build by hand" >&2
    echo "  (pm uninstall $VOX, which wipes its data) and run this again. Nothing else was changed." >&2
    exit 1 ;;
  *) echo "ERROR: pm install failed; Home was not touched." >&2; exit 1 ;;
esac

# A fresh start of the new build, then require that it really came up before Home is handed to it.
# "Status: ok" alone is not enough: some builds print it for a task merely brought to the front.
START=$(tv "am force-stop $VOX; am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n $VOX_HOME")
echo "$START"
if ! printf '%s' "$START" | grep -qF 'Status: ok' || printf '%s' "$START" | grep -qi '^Error'; then
  echo "ERROR: Vox did not start (see above); Home was not handed over." >&2
  exit 1
fi

# Wi-Fi name in the header (Android 10+), and the screensaver's foreground app (lane 4) from UsageStats.
printf '%s\n' \
  "pm grant $VOX android.permission.ACCESS_FINE_LOCATION && echo location granted" \
  "appops set $VOX GET_USAGE_STATS allow && appops write-settings && echo usage stats allowed" \
  "cmd package compile -m speed -f $VOX" \
  "cmd package set-home-activity --user 0 $VOX_HOME" \
  | tv --batch

focused() { tv 'dumpsys activity activities | grep -m 1 mFocusedApp' 2>/dev/null | tr -d '\r' || true; }

# Google TV can print Success for set-home-activity and change nothing, so press Home and see what comes up.
confirm_home() {   # $1: package expected in front after HOME
  tv 'input keyevent KEYCODE_HOME' >/dev/null 2>&1 || true
  sleep 2
  case "$(focused)" in *"$1"/*) return 0 ;; esac
  return 1
}

if confirm_home "$VOX"; then
  echo "Home is Vox: $(tv "$HOME_QUERY" | tail -n 1 | tr -d '\r')"
else
  echo "ERROR: after HOME the screen shows '$(focused)', not Vox. Setting Home back to upstream." >&2
  tv "cmd package set-home-activity --user 0 $UPSTREAM_HOME" >/dev/null 2>&1 || true
  if confirm_home com.gathin.tvhome; then
    echo "Upstream's launcher is Home again." >&2
  else
    echo "ERROR: rollback unconfirmed; Home shows '$(focused)'. Run by hand:" >&2
    echo "  TV_HOST=<tv ip> tools/tv.py 'cmd package set-home-activity --user 0 $UPSTREAM_HOME'" >&2
  fi
  exit 1
fi

# Upstream's HomeKeyService runs the remote's YouTube and Netflix buttons with Vox as Home (spikes/vox/FINDINGS.md).
# Put it back if it fell off the enabled list. A service already listed binds again only when the list changes, so a
# missing one is appended without dropping the others (as launcher/build-and-install.sh does).
SERVICES=$(tv 'settings get secure enabled_accessibility_services' | tail -n 1 | tr -d '\r')
case "$SERVICES" in
  *"$KEY_SERVICE"*) echo "HomeKeyService is enabled." ;;
  *)
    case "$SERVICES" in null|'') NEW=$KEY_SERVICE ;; *) NEW="$SERVICES:$KEY_SERVICE" ;; esac
    tv "settings put secure enabled_accessibility_services '$NEW'; settings put secure accessibility_enabled 1" >/dev/null
    case "$(tv 'settings get secure enabled_accessibility_services' | tail -n 1)" in
      *"$KEY_SERVICE"*) echo "HomeKeyService was off; enabled it again." ;;
      *) echo "WARNING: could not enable $KEY_SERVICE; the remote's YouTube/Netflix buttons may do nothing." >&2 ;;
    esac ;;
esac
echo "Installed. Vox is Home; upstream's launcher stays installed as the fallback."
