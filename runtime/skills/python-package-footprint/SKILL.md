---
name: python-package-footprint
description: Measure and report a Python library's real cost — wheel size, full dependency download, installed disk size, and import/runtime memory — so "is this package heavy?" gets answered with numbers instead of guesses. Use when a user asks whether a library is big/slow to install, compares libraries by weight, or plans a Docker image / CI layer budget.
---

# Python package footprint measurement

Answers "这个库装起来重吗 / 占多少内存" with measured numbers, not vendor claims.
Self-reported doc numbers are almost always the **wheel** size, which understates the
installed footprint by 10–20× (numpy, fonts, compiled binaries expand on unzip).

## Four metrics — never report just one

| metric | what it answers | how |
| --- | --- | --- |
| wheel 本体 | package's own code | `pip download --no-deps` |
| 含依赖下载 | bytes over the wire | `pip download` (with deps) |
| 装完占磁盘 | image layer growth | `pip install --target DIR` + `du -sm` |
| 导入峰值 RSS | runtime cost | import, read `ru_maxrss` |

Optional fifth: task-specific RSS (e.g. open a plotter / render offscreen), which is
often 2–3× the bare import number.

## Procedure

1. Capture the environment first: python version, platform, whether the package is
   already installed, and free disk. Numbers are meaningless without it.
2. `pip download --only-binary=:all:` each target into its own dir — wheels only, so you
   measure what a normal user actually gets and never trigger a source build.
3. Install each into an **isolated `--target` dir** (never the live env), then `du -sm`.
4. Import with `PYTHONPATH=<target>` in a **fresh process** and read
   `resource.getrusage(RUSAGE_SELF).ru_maxrss/1024` for MB.
5. For 3D/plotting libs, also exercise the real path (e.g. `pv.Plotter(off_screen=True)`
   + `screenshot()`), since GL/VTK init dominates.
6. Report the ratio, not just the number: "wheel 2.6 MB → installed 767 MB (295×) because
   VTK ships 565 MB of `vtkmodules`" is the sentence the user remembers.
7. Clean up: remove `--target` dirs **and** `pip cache purge` (wheels land in
   `~/.cache/pip` and can silently hold hundreds of MB).

`scripts/footprint.sh <pkg...>` runs steps 2–4 and prints a table.

## Pitfalls seen in practice

- **`/tmp` is often tmpfs and small (2 GB in e2b sandboxes).** A GB-class install
  (pyvista, open3d) dies with `OSError: [Errno 28] No space left on device` mid-install.
  Point both the target dir and `TMPDIR` at the workspace disk:
  `TMPDIR=$PWD/.tmpinst/tmp pip install --target $PWD/.tmpinst/x ...`
  `df -h /tmp` vs `df -h .` reveals this before you waste a download.
- **`--no-deps` + `--find-links` fails on unrelated missing deps.** Reinstalling the
  same package *with* deps into a second target dir is faster than chasing the chain;
  `--no-deps` is only for isolating the package's own wheel.
- **Version pinning matters more than the library name** — state the version you measured.
- **`du` on a `--target` dir includes everything else you put there.** One dir per package.
- A pip resolver conflict warning (`numba requires numpy<2.5`) usually means the target
  dir's numpy shadowed the global one; harmless for size measurement, note it if the
  number looks off.
- **Don't leave the installs behind.** Report that you removed them and from where —
  users ask "你刚才装了哪些" and expect a truthful accounting, not a vague "cleaned up".

## Reporting shape

Lead with the verdict tier (`轻 / 中偏重 / 重`), then a 5-row table:
库 | 本体 wheel | 含依赖下载 | 装完占磁盘 | 导入内存. Add one line per package saying
*why* it is heavy (VTK binaries, CUDA, Qt, bundled JS) and one line saying how to avoid it
(CDN instead of bundling, `--no-deps`, separate image layer, optional extra).

See `references/measured-2026-11.md` for a snapshot of common data-viz libraries with the
exact commands and environment used, so a follow-up question doesn't need a re-measure.