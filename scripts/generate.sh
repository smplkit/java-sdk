#!/usr/bin/env bash
#
# Regenerate all client code from OpenAPI specs.
#
# Prerequisites:
#   - openapi-generator installed (https://openapi-generator.tech)
#     brew install openapi-generator
#
# Usage:
#   ./scripts/generate.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
SPEC_DIR="$PROJECT_DIR/openapi"
SOURCE_ROOT="$PROJECT_DIR/src/main/java"

echo "Regenerating clients from OpenAPI specs..."

for spec in "$SPEC_DIR"/*.json; do
    name=$(basename "$spec" .json)
    pkg_base="com.smplkit.internal.generated.${name}"
    pkg_path="${SOURCE_ROOT}/com/smplkit/internal/generated/${name}"

    echo "  Generating $name from $(basename "$spec")..."

    # Generate to a temp directory to avoid polluting the source tree with
    # Maven project scaffolding (pom.xml, README, test files, etc.)
    temp_dir=$(mktemp -d)

    openapi-generator generate \
        -i "$spec" \
        -g java \
        -o "$temp_dir" \
        --additional-properties="library=native,useJakartaEe=true,invokerPackage=${pkg_base},apiPackage=${pkg_base}.api,modelPackage=${pkg_base}.model" \
        2>&1 | tail -1

    # Remove previously generated Java files for this spec (preserve package-info.java)
    if [ -d "$pkg_path" ]; then
        find "$pkg_path" -name "*.java" -not -name "package-info.java" -delete
    fi
    mkdir -p "$pkg_path"

    # Copy only the generated Java source files into the source tree
    src_in_temp="${temp_dir}/src/main/java/com/smplkit/internal/generated/${name}"
    if [ -d "$src_in_temp" ]; then
        cp -r "$src_in_temp/." "$pkg_path/"
    fi

    rm -rf "$temp_dir"

    echo "  Done: $name"
done

echo "All clients regenerated."
