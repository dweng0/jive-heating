#!/usr/bin/env bash
# Renders the home-screen icons in public/ from icons/app-icon.svg (the favicon's flame on a
# full-bleed square, so iOS and Android masks can round it themselves; the flame sits inside the
# maskable safe zone). Run after changing the icon and commit the PNGs. Needs Inkscape.
set -euo pipefail
web="$(cd "$(dirname "$0")/.." && pwd)"
src="$web/icons/app-icon.svg"
render() { inkscape "$src" --export-type=png --export-width="$1" --export-height="$1" --export-filename="$web/public/$2" >/dev/null 2>&1; }
render 192 icon-192.png
render 512 icon-512.png
render 180 apple-touch-icon.png
