#!/bin/sh
# Render the store's 512 px icon from the one drawing in this directory.
#
#   artwork/render-icon.sh          rewrite fastlane's icon.png
#   artwork/render-icon.sh --check  render to a temporary file and fail if the
#                                   committed icon.png is not that rendering
#
# `nix flake check` runs the second form (the `icon` check), so an edit to
# launcher-icon.svg that nobody re-rendered fails the tree instead of shipping a
# listing whose picture is a version behind. Run this inside `nix develop`, or
# with RSVG_CONVERT pointing at an rsvg-convert on the PATH.
#
# The two adaptive layers (res/drawable/ic_launcher_{background,foreground}.xml)
# are hand-split from the same SVG: the check can only see the whole icon, so a
# change to the layers still has to be made in both places.
set -eu

here=$(cd -- "$(dirname -- "$0")" && pwd -P)
source=$here/launcher-icon.svg
out=$here/../fastlane/metadata/android/en-US/images/icon.png
rsvg=${RSVG_CONVERT:-rsvg-convert}

if ! command -v "$rsvg" >/dev/null 2>&1; then
    echo "render-icon.sh: $rsvg is not on PATH; run inside 'nix develop'" >&2
    exit 2
fi

render() {
    # 512 x 512: the size both stores ask for. rsvg-convert is deterministic for
    # a given version, which is what lets --check be a byte comparison.
    "$rsvg" --width 512 --height 512 --output "$1" "$source"
}

case ${1:-} in
'')
    render "$out"
    echo "wrote $out"
    ;;
--check)
    tmp=$(mktemp)
    trap 'rm -f "$tmp"' EXIT
    render "$tmp"
    if ! cmp -s "$tmp" "$out"; then
        echo "render-icon.sh: $out is not the rendering of $source" >&2
        echo "  run artwork/render-icon.sh and commit the result" >&2
        exit 1
    fi
    echo "icon.png matches launcher-icon.svg"
    ;;
*)
    echo "usage: $0 [--check]" >&2
    exit 2
    ;;
esac
