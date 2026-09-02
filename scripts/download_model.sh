#!/bin/sh
set -eu
"$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/download_models.sh"
