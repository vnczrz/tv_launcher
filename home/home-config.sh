#!/usr/bin/env bash
# Push a config.json to Vox (home/, tv.voxclean.launcher) and restart it so it reads the file.
#   TV_HOST=<tv ip> home/home-config.sh path/to/config.json
#   TV_HOST=<tv ip> home/home-config.sh --reset       remove it: every value back to the default
# Keys (all optional; a bad value is ignored on its own, a file that does not parse is ignored as a whole):
#   focus_scale, greetings {morning|afternoon|evening|late: [lines]}, app_lines, live_uri, hidden_apps,
#   saver_entry_speed, saver_tracking_em.
# The file goes to Vox's external files directory (/sdcard/Android/data/<pkg>/files), which the shell can write on
# Android 14; there the copy belongs to Vox's uid and Vox reads it (Google TV 14). logcat -s Vox prints what it read.
set -euo pipefail
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0 config.json}"
# TOOLS=<dir>: a tools/ with its venv and ADB key (default: this repo's tools/).
TOOLS="$(cd "${TOOLS:-$(dirname "$0")/../tools}" && pwd)"
PY=$TOOLS/venv/bin/python
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
tv() { "$PY" -I "$TOOLS/tv.py" "$@"; }
PKG=tv.voxclean.launcher
DIR=/sdcard/Android/data/$PKG/files
# Restart through the Home intent when Vox is Home, so the next Home press does not start a second copy.
RESTART="am force-stop $PKG; case \"\$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1)\" in $PKG/*) am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1 ;; *) am start -n $PKG/.MainActivity >/dev/null 2>&1 ;; esac"

case "${1:-}" in
  --reset)
    tv "rm -f $DIR/config.json; $RESTART; echo config.json removed"
    exit 0 ;;
  ''|-*) sed -n '2,9p' "$0" >&2; exit 1 ;;
esac
CONFIG=$1
[ -f "$CONFIG" ] || { echo "ERROR: no such file: $1" >&2; exit 1; }
# Vox would ignore a broken file silently; say so here instead.
ERR=$(python3 -I -c 'import json, sys; json.load(open(sys.argv[1], encoding="utf-8"))' "$CONFIG" 2>&1 | tail -n 1) \
  || { echo "ERROR: $1 is not valid JSON ($ERR); nothing was pushed." >&2; exit 1; }
tv --push "$CONFIG" /data/local/tmp/vox-config.json >/dev/null
tv "mkdir -p $DIR && rm -f $DIR/config.json && cp /data/local/tmp/vox-config.json $DIR/config.json && chmod 666 $DIR/config.json; rm -f /data/local/tmp/vox-config.json; $RESTART; echo config.json pushed"
