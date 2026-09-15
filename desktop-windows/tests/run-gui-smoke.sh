#!/usr/bin/env bash
set -euo pipefail
# Keep the top-level verifier entrypoint while testing the current two-step UI.
test_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec bash "${test_dir}/run-workspace-gui.sh"
