#!/usr/bin/env bash
#
# Regenerate all client code from OpenAPI specs.
#
# Prerequisites:
#   - openapi-generator installed (https://openapi-generator.tech)
#     Homebrew:  brew install openapi-generator
#     npm:       npm install -g @openapitools/openapi-generator-cli
#
# Usage:
#   ./scripts/generate.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
SPEC_DIR="$PROJECT_DIR/openapi"
SOURCE_ROOT="$PROJECT_DIR/src/main/java"

# Find openapi-generator (Homebrew: openapi-generator, npm: openapi-generator-cli)
if command -v openapi-generator >/dev/null 2>&1; then
    OPENAPI_GEN="openapi-generator"
elif command -v openapi-generator-cli >/dev/null 2>&1; then
    OPENAPI_GEN="openapi-generator-cli"
else
    echo "Error: openapi-generator not found."
    echo "Install via: brew install openapi-generator"
    echo "         or: npm install -g @openapitools/openapi-generator-cli"
    exit 1
fi

echo "Regenerating clients from OpenAPI specs (using $OPENAPI_GEN)..."

DOWNGRADE="$SCRIPT_DIR/downgrade-spec.py"

for spec in "$SPEC_DIR"/*.json; do
    name=$(basename "$spec" .json)
    pkg_base="com.smplkit.internal.generated.${name}"
    pkg_path="${SOURCE_ROOT}/com/smplkit/internal/generated/${name}"

    echo "  Generating $name from $(basename "$spec")..."

    # openapi-generator produces broken AnyOf references for OpenAPI 3.1
    # anyOf-with-null patterns. Downgrade to 3.0 if needed.
    gen_spec="$spec"
    if python3 -c "import json,sys; sys.exit(0 if json.load(open('$spec')).get('openapi','').startswith('3.1') else 1)" 2>/dev/null; then
        gen_spec=$(mktemp).json
        python3 "$DOWNGRADE" "$spec" > "$gen_spec"
        echo "    Downgraded $name spec from 3.1 to 3.0.3"
    fi

    # Generate to a temp directory to avoid polluting the source tree with
    # Maven project scaffolding (pom.xml, README, test files, etc.)
    temp_dir=$(mktemp -d)

    $OPENAPI_GEN generate \
        -i "$gen_spec" \
        -g java \
        -o "$temp_dir" \
        --additional-properties="library=native,useJakartaEe=true,hideGenerationTimestamp=true,invokerPackage=${pkg_base},apiPackage=${pkg_base}.api,modelPackage=${pkg_base}.model" \
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
    [ "$gen_spec" != "$spec" ] && rm -f "$gen_spec"

    echo "  Done: $name"
done

echo "All clients regenerated."
