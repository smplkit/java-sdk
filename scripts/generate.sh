#!/usr/bin/env bash
#
# Regenerate all client code from OpenAPI specs.
#
# Prerequisites:
#   - openapi-generator-cli installed (https://openapi-generator.tech)
#
# Usage:
#   ./scripts/generate.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
SPEC_DIR="$PROJECT_DIR/openapi"
OUTPUT_BASE="$PROJECT_DIR/src/main/java/com/smplkit/internal/generated"

echo "Regenerating clients from OpenAPI specs..."

for spec in "$SPEC_DIR"/*.json; do
    name=$(basename "$spec" .json)
    output_dir="$OUTPUT_BASE/$name"

    echo "  Generating $name from $(basename "$spec")..."

    # Clean existing generated code
    rm -rf "$output_dir"
    mkdir -p "$output_dir"

    openapi-generator-cli generate \
        -i "$spec" \
        -g java \
        -o "$output_dir" \
        --package-name "com.smplkit.internal.generated.$name" \
        --additional-properties=library=native,useJakartaEe=true \
        2>&1 | tail -1

    echo "  Done: $name"
done

echo "All clients regenerated."
