#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
cat src/1-head.html src/2-css.html src/3-body.html src/4-data.js src/5-render.js src/6-app.js > index.html
echo "wrote index.html ($(wc -c < index.html) bytes)"
