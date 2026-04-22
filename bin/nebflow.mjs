#!/usr/bin/env node
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { spawn } from "node:child_process";

const __dirname = dirname(fileURLToPath(import.meta.url));
const tsxPath = resolve(__dirname, "..", "node_modules", "tsx", "dist", "esm", "index.mjs");
const entryPath = resolve(__dirname, "..", "cli", "nebflow.ts");

const child = spawn(
  process.execPath,
  ["--import", tsxPath, entryPath, ...process.argv.slice(2)],
  { stdio: "inherit" }
);
child.on("exit", (code) => process.exit(code ?? 0));
