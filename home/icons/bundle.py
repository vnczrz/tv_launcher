#!/usr/bin/env python3
"""Recolour the voxel icons' #F4F4F4 background to the tile colour and downscale them for Vox's tiles.

    .toolchain/icon-venv/bin/python home/icons/bundle.py

Reads home/icons/final/*.png (the masters, never changed) and writes
home/app/src/main/res/drawable-nodpi/vox_<name>.png. The background is the region flooded from the corners at
exactly #F4F4F4 (the voxel-icon normaliser wrote it that way); the antialiased ring around the subject is
re-matted with the normaliser's own edge-alpha estimate, so no light halo is left on the darker tile.

Background showing through a closed shape (a letter's counter, the gaps in the Live tower) was never normalised,
and white voxel faces render at the same grey, so those pockets cannot be told apart by colour: POCKETS lists a
seed pixel (master coordinates) in each one, picked by eye from an overlay of the candidate regions.
"""

from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

OLD = np.array([244, 244, 244], dtype=np.float32)
NEW = (232, 232, 230)   # tile colour #E8E8E6
SIZE = 400
FEATHER_LIMIT = 12
POCKET_TOLERANCE = 3
POCKETS = {
    "dazn": [(198, 524), (467, 553)],
    "fubo": [(708, 658), (979, 712)],
    "hbo-max": [(599, 540), (892, 605), (968, 654), (583, 691)],
    "live": [(594, 428), (659, 428), (670, 553), (588, 557), (599, 676), (568, 746), (682, 747), (592, 870),
             (659, 870)],
    "peacock": [(496, 398)],
    "philo": [(235, 591), (1013, 708)],
    "sling": [(1025, 665)],
    "starz": [(593, 588), (839, 603)],
    "tubi": [(796, 671)],
}
HOME = Path(__file__).resolve().parent.parent
SRC = HOME / "icons" / "final"
OUT = HOME / "app" / "src" / "main" / "res" / "drawable-nodpi"


def flood(candidate, seeds):
    mask = Image.fromarray(np.where(candidate, 255, 0).astype(np.uint8)).copy()
    for seed in seeds:
        if mask.getpixel(seed) == 255:
            ImageDraw.floodfill(mask, seed, 128, thresh=0)
    return np.asarray(mask) == 128


def background(rgb, pockets):
    h, w = rgb.shape[:2]
    bg = flood((rgb == OLD.astype(np.uint8)).all(axis=2), ((0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)))
    near = np.abs(rgb.astype(np.int16) - OLD.astype(np.int16)).max(axis=2) <= POCKET_TOLERANCE
    for x, y in pockets:
        if not near[y, x]:
            raise SystemExit(f"pocket seed {(x, y)} is not background grey")
    return bg | flood(near & ~bg, pockets)


def recolour(rgb, pockets=()):
    bg = background(rgb, pockets)
    delta = np.abs(rgb.astype(np.float32) - OLD).max(axis=2)
    # The ring: pixels within 2 px of the background. Each one's coverage is its distance from the old
    # background over the strongest neighbour's distance (the normaliser's estimate), and only the
    # uncovered share takes the new colour.
    ring = (np.asarray(Image.fromarray(bg.astype(np.uint8) * 255).filter(ImageFilter.MaxFilter(5))) > 0) & ~bg
    padded = np.pad(delta, 2, mode="edge")
    best = delta.copy()
    for dy in range(5):
        for dx in range(5):
            best = np.maximum(best, padded[dy:dy + delta.shape[0], dx:dx + delta.shape[1]])
    coverage = np.clip(delta / np.maximum(best, FEATHER_LIMIT), 0, 1)
    shift = np.array(NEW, dtype=np.float32) - OLD
    out = rgb.astype(np.float32)
    out[ring] += (1 - coverage[ring])[:, None] * shift
    out[bg] = NEW
    return np.clip(np.round(out), 0, 255).astype(np.uint8)


def main():
    for src in sorted(SRC.glob("*.png")):
        rgb = np.asarray(Image.open(src).convert("RGB"))
        small = Image.fromarray(recolour(rgb, POCKETS.get(src.stem, ()))).resize((SIZE, SIZE), Image.LANCZOS)
        a = np.asarray(small)
        corners = {tuple(int(v) for v in a[y, x]) for y in (0, -1) for x in (0, -1)}
        if corners != {NEW}:
            raise SystemExit(f"{src.name}: corners {corners}, expected {NEW}")
        dest = OUT / ("vox_" + src.stem.replace("-", "_") + ".png")
        small.save(dest, optimize=True)
        print(f"{dest.name} {dest.stat().st_size // 1024} KB")


if __name__ == "__main__":
    main()
