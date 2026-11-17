#!/usr/bin/env bash
# Measure wheel / download / installed size for one or more PyPI packages.
# Usage: footprint.sh pandas polars pyvista
# Installs into a workspace-local temp dir (NOT the live environment) and cleans up after.
set -uo pipefail

[ $# -eq 0 ] && { echo "usage: $0 <pkg> [pkg...]"; exit 2; }

WORK="$(pwd)/.footprint-tmp"
mkdir -p "$WORK"
export TMPDIR="$WORK/tmp"; mkdir -p "$TMPDIR"
# GB-class installs overflow a small tmpfs /tmp; warn early.
avail_kb=$(df -Pk "$(pwd)" | awk 'NR==2{print $4}')
echo "# python $(python3 -V 2>&1 | cut -d' ' -f2) | target disk avail ${avail_kb}KB" >&2

printf "%-16s %10s %12s %10s\n" pkg wheel_MB deps_dl_MB installed_MB
for p in "$@"; do
  d="$WORK/dl/$p"; i="$WORK/inst/$p"
  mkdir -p "$d" "$i"
  pip download --only-binary=:all: --no-deps -q -d "$d" "$p" >/dev/null 2>&1 || { echo "$p download FAILED" >&2; continue; }
  wheel=$(find "$d" -name '*.whl' -printf '%s\n' | sort -n | tail -1)
  wheel=$(awk -v b="${wheel:-0}" 'BEGIN{printf "%.1f", b/1048576}')
  mkdir -p "$WORK/all/$p"
  pip download --only-binary=:all: -q -d "$WORK/all/$p" "$p" >/dev/null 2>&1
  deps=$(du -sm "$WORK/all/$p" 2>/dev/null | cut -f1)
  pip install -q --target "$i" --find-links "$WORK/all/$p" --no-index "$p" >/dev/null 2>&1 \
    || pip install -q --target "$i" "$p" >/dev/null 2>&1
  inst=$(du -sm "$i" 2>/dev/null | cut -f1)
  printf "%-16s %10s %12s %10s\n" "$p" "${wheel:-?}" "${deps:-?}" "${inst:-?}"
  # bare-import peak RSS in a fresh interpreter, isolated to the target dir
  PYTHONPATH="$i" python3 - "$p" <<'PY' 2>/dev/null || true
import importlib, resource, sys
try:
    importlib.import_module(sys.argv[1].replace("-", "_").split("[")[0])
    print(f"  import RSS: {resource.getrusage(resource.RUSAGE_SELF).ru_maxrss/1024:.0f} MB", file=sys.stderr)
except Exception as e:
    print(f"  import failed: {type(e).__name__}", file=sys.stderr)
PY
done

rm -rf "$WORK"
pip cache purge >/dev/null 2>&1 || true
echo "# cleaned $WORK and pip cache" >&2