import assert from "node:assert/strict";
import {
  chmodSync,
  existsSync,
  mkdtempSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { spawnSync } from "node:child_process";
import { test } from "vitest";

function runWithVersions(nodeVersion, pnpmVersion) {
  const script = resolve(import.meta.dirname, "..", "start.sh");
  const gitBash = "C:\\Program Files\\Git\\bin\\bash.exe";
  const bash =
    process.platform === "win32" && existsSync(gitBash) ? gitBash : "bash";
  const bashScript =
    process.platform === "win32"
      ? script
          .replace(/^([A-Za-z]):\\/, (_, drive) => `/${drive.toLowerCase()}/`)
          .replaceAll("\\", "/")
      : script;
  const binDir = mkdtempSync(join(tmpdir(), "cute-start-guard-"));
  try {
    for (const [name, version] of [
      ["node", nodeVersion],
      ["pnpm", pnpmVersion],
    ]) {
      const executable = join(binDir, name);
      writeFileSync(executable, `#!/bin/sh\nprintf '%s\\n' '${version}'\n`);
      chmodSync(executable, 0o755);
    }
    // pnpm install / dev are intentionally replaced by a no-op: this test
    // only exercises the guards before the install step.
    return spawnSync(bash, [bashScript], {
      encoding: "utf8",
      env: {
        ...process.env,
        PATH:
          process.platform === "win32"
            ? `${binDir.replaceAll("\\", "/")}:${process.env.PATH ?? ""}`
            : `${binDir}:${process.env.PATH ?? ""}`,
      },
    });
  } finally {
    rmSync(binDir, { recursive: true, force: true });
  }
}

test("accepts Node 18.17 and pnpm 10.29 without integer errors", () => {
  const result = runWithVersions("v18.17.0", "10.29.3");

  assert.equal(result.status, 0, result.stderr);
  assert.doesNotMatch(
    result.stderr,
    /integer expected|integer expression expected/,
  );
});

test("accepts Node 18 and pnpm 7 major versions", () => {
  const result = runWithVersions("v18.0.0", "7.0.0");

  assert.equal(result.status, 0, result.stderr);
  assert.doesNotMatch(
    result.stderr,
    /integer expected|integer expression expected/,
  );
});

test("rejects Node 17 and pnpm 6 by major version", () => {
  const result = runWithVersions("v17.9.0", "6.9.0");

  assert.notEqual(result.status, 0);
  assert.match(result.stdout, /Node\.js version 18 is required/);
});

test("rejects pnpm 6 when Node satisfies the minimum", () => {
  const result = runWithVersions("v18.17.0", "6.9.0");

  assert.notEqual(result.status, 0);
  assert.match(result.stdout, /pnpm version 7 is required/);
});
