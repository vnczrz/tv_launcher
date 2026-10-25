#!/usr/bin/env bash
# Generate one voxel app icon with gpt-image-2 through the codex CLI, attaching reference images.
# Same approach as the gpt-image-bridge skill, plus `-i` so the voxel-icon calibration sheets (and, after the
# first approved icon, that icon) go in as references.
#
# Usage: home/icons/generate.sh <prompt.txt> <raw/out.png> [reference.png ...]
set -euo pipefail

prompt_file="${1:?usage: generate.sh <prompt.txt> <out.png> [reference.png ...]}"
out="${2:?usage: generate.sh <prompt.txt> <out.png> [reference.png ...]}"
shift 2

command -v codex >/dev/null || { echo "codex CLI not found: brew install codex && codex login" >&2; exit 127; }
[ -r "$prompt_file" ] || { echo "cannot read prompt: $prompt_file" >&2; exit 2; }

refs=()
for r in "$@"; do
  [ -r "$r" ] || { echo "cannot read reference: $r" >&2; exit 2; }
  refs+=(-i "$(cd "$(dirname "$r")" && pwd)/$(basename "$r")")
done

out_dir="$(mkdir -p "$(dirname "$out")" && cd "$(dirname "$out")" && pwd)"
out="$out_dir/$(basename "$out")"
# Codex may only write inside its own working directory; the copy to $out happens here.
workdir="$(mktemp -d "${TMPDIR:-/tmp}/voxel-icon.XXXXXX")"
log="$workdir.log"

full_prompt="Use your image_generation tool (gpt-image-2) to create one image.
The attached images are calibration references for construction, camera, material and lighting only; do not copy their subjects.

PROMPT:
$(cat "$prompt_file")
SIZE: 1254x1254

Requirements:
- You MUST call the image_generation tool. Do not write a script or fabricate a PNG another way.
- Save the PNG as out.png in your current working directory (relative path).
- Reply with only the file name."

# `-i` takes several files, so `--` stops it from swallowing the prompt as one more image.
if ! codex exec --skip-git-repo-check -s workspace-write -C "$workdir" "${refs[@]}" -- "$full_prompt" </dev/null >"$log" 2>&1; then
  echo "codex exec failed; last lines of $log:" >&2
  tail -n 30 "$log" >&2
  exit 1
fi
img="$workdir/out.png"
[ -f "$img" ] || img="$(ls -t "$workdir"/*.png 2>/dev/null | head -n 1 || true)"
if [ -z "$img" ] || [ ! -f "$img" ]; then
  echo "codex produced no PNG; last lines of $log:" >&2
  tail -n 30 "$log" >&2
  exit 1
fi
cp "$img" "$out"
rm -rf "$workdir" "$log" 2>/dev/null || true
echo "$out"
