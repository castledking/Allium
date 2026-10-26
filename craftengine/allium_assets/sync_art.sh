#!/usr/bin/env bash
# Refreshes retained tomato seed/produce art. Plant-stage art is migrated
# separately by import_nexo_crops.py and must keep its measured alignment.
#
# The art belongs to the server's resource pack, not to Allium, so it is not
# committed to this repository — run this on the server (or against a copy of
# the server's plugins folder) before deploying the pack.
#
#   ./sync_art.sh /path/to/plugins
#
# Where it lands:
#   resourcepack/assets/ce/textures/item/crops/tomato/...    the item sprites
#   resourcepack/assets/ce/textures/block/crops/tomato/...   the block sprites
#   resourcepack/assets/ce/models/item/crops/tomato/...       the stage models
#   resourcepack/assets/ce/models/block/crops/tomato/...      the block models
#
# The item/ and block/ split is not cosmetic: a sprite's path decides which
# atlas it lands in, and an item model can only use sprites from the item
# atlas. A texture under textures/crops/... renders untextured.
#
# Every texture a model names is copied into the atlas that model is drawn
# in, because the two need different copies of the same picture.

set -euo pipefail

PLUGINS="${1:-/home/container/plugins}"
NEXO="$PLUGINS/Nexo/pack/assets/sf"
PACK="$PLUGINS/CraftEngine/resources/allium_assets"
HERE="$(cd "$(dirname "$0")" && pwd)"

if [ ! -d "$NEXO" ]; then
    echo "Nexo pack not found at $NEXO" >&2
    exit 1
fi

# When the pack lives in this repository, write the art next to it instead of
# into the server's copy.
if [ -d "$HERE/configuration" ]; then
    PACK="$HERE"
fi

# Copies a model into one atlas and copies every texture it names into that
# same atlas, rewriting the references as it goes.
sync_model() {
    local model="$1" atlas="$2" target="$3"
    mkdir -p "$(dirname "$target")"
    sed -E "s|\"sf:([a-z0-9_/]+)\"|\"ce:$atlas/\1\"|g" "$model" > "$target"
    for reference in $(grep -oE '"sf:[a-z0-9_/]+"' "$model" | tr -d '"' | sort -u); do
        local path="${reference#sf:}"
        mkdir -p "$PACK/resourcepack/assets/ce/textures/$atlas/$(dirname "$path")"
        cp "$NEXO/textures/$path.png" \
           "$PACK/resourcepack/assets/ce/textures/$atlas/$path.png"
    done
}

# The gigantic tomato is a three dimensional item model.
sync_model "$NEXO/models/crops/tomato/gigantic_tomato.json" "item" \
    "$PACK/resourcepack/assets/ce/models/item/crops/tomato/gigantic_tomato.json"

# The seeds and the fruit are flat item models.
mkdir -p "$PACK/resourcepack/assets/ce/textures/item/crops/tomato"
for texture in tomato tomato_seeds golden_tomato tomato_silver_star tomato_golden_star; do
    cp "$NEXO/textures/crops/tomato/$texture.png" \
       "$PACK/resourcepack/assets/ce/textures/item/crops/tomato/$texture.png"
done

echo "art synced into $PACK/resourcepack/assets/ce"
