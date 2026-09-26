/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

// The launcher against a local stand-in for the GitHub release: a tiny bundle whose bin/petek echoes its arguments.
// Runs where `tar` exists (Linux, macOS, Windows 10+); the bundle's launcher is a shell script, so not on Windows.

'use strict';

const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const { execFileSync, spawn } = require('node:child_process');
const { test } = require('node:test');

const LAUNCHER = path.join(__dirname, '..', 'bin', 'petek.js');
const VERSION = '9.9.9-test';
const PLATFORM = process.platform === 'darwin' ? (process.arch === 'arm64' ? 'mac-arm64' : 'mac-x64') : process.arch === 'arm64' ? 'linux-arm64' : 'linux-x64';
const ARCHIVE = `petek-${VERSION}-${PLATFORM}.tar.gz`;

/** A bundle whose launcher prints its arguments and exits with the code given as PETEK_TEST_EXIT. */
function fakeBundle(dir) {
  const root = path.join(dir, `petek-${VERSION}-${PLATFORM}`);
  fs.mkdirSync(path.join(root, 'bin'), { recursive: true });
  fs.mkdirSync(path.join(root, 'runtime'), { recursive: true });
  fs.writeFileSync(path.join(root, 'bin', 'petek'), '#!/bin/sh\necho "fake petek: $*"\nexit "${PETEK_TEST_EXIT:-0}"\n', { mode: 0o755 });
  const archive = path.join(dir, ARCHIVE);
  execFileSync('tar', ['-czf', archive, '-C', dir, path.basename(root)]);
  return fs.readFileSync(archive);
}

function serve(files) {
  const hits = [];
  const server = http.createServer((request, response) => {
    hits.push(request.url);
    const body = files[path.posix.basename(request.url)];
    if (!body) {
      response.writeHead(404).end();
      return;
    }
    response.writeHead(200).end(body);
  });
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => resolve({ server, hits, base: `http://127.0.0.1:${server.address().port}/v${VERSION}` }));
  });
}

/** Runs the launcher; asynchronous, because the stand-in release server lives in this same process. */
function run(args, env) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [LAUNCHER, ...args], { env: { ...process.env, ...env } });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (chunk) => (stdout += chunk));
    child.stderr.on('data', (chunk) => (stderr += chunk));
    child.on('error', reject);
    child.on('close', (status) => resolve({ status, stdout, stderr }));
  });
}

test('the launcher downloads, verifies and extracts the bundle once, then runs it with the arguments', { skip: process.platform === 'win32' }, async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'petek-launcher-'));
  const home = path.join(dir, 'home');
  const bundle = fakeBundle(path.join(dir, 'release'));
  const sums = `${crypto.createHash('sha256').update(bundle).digest('hex')}  ${ARCHIVE}\nabc  other.zip\n`;
  const { server, hits, base } = await serve({ [ARCHIVE]: bundle, SHA256SUMS: sums });
  const env = { PETEK_VERSION: VERSION, PETEK_DOWNLOAD_BASE: base, PETEK_HOME: home };
  try {
    const first = await run(['doctor', '--verbose'], env);
    assert.equal(first.status, 0, first.stderr);
    assert.equal(first.stdout, 'fake petek: doctor --verbose\n');
    assert.match(first.stderr, /downloading petek-9\.9\.9-test-.*\.tar\.gz/);
    assert.ok(fs.existsSync(path.join(home, 'versions', VERSION, `petek-${VERSION}-${PLATFORM}`, 'bin', 'petek')));
    assert.equal(fs.readdirSync(path.join(home, 'downloads')).length, 0, 'the archive is removed after extraction');
    assert.deepEqual(hits.sort(), [`/v${VERSION}/SHA256SUMS`, `/v${VERSION}/${ARCHIVE}`]);

    const second = await run(['panel'], { ...env, PETEK_TEST_EXIT: '3' });
    assert.equal(second.status, 3);
    assert.equal(second.stdout, 'fake petek: panel\n');
    assert.equal(second.stderr, '');
    assert.equal(hits.length, 2, 'the second run does not download again');
  } finally {
    server.close();
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('a bundle whose checksum does not match is refused and nothing is installed', { skip: process.platform === 'win32' }, async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'petek-launcher-'));
  const home = path.join(dir, 'home');
  const bundle = fakeBundle(path.join(dir, 'release'));
  const { server, base } = await serve({ [ARCHIVE]: bundle, SHA256SUMS: `${'0'.repeat(64)}  ${ARCHIVE}\n` });
  try {
    const result = await run(['doctor'], { PETEK_VERSION: VERSION, PETEK_DOWNLOAD_BASE: base, PETEK_HOME: home });
    assert.equal(result.status, 2);
    assert.match(result.stderr, /checksum mismatch/);
    assert.equal(fs.existsSync(path.join(home, 'versions', VERSION)), false);
  } finally {
    server.close();
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('a missing release is reported with the releases page', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'petek-launcher-'));
  const { server, base } = await serve({});
  try {
    const result = await run(['doctor'], { PETEK_VERSION: VERSION, PETEK_DOWNLOAD_BASE: base, PETEK_HOME: path.join(dir, 'home') });
    assert.equal(result.status, 2);
    assert.match(result.stderr, /HTTP 404/);
    assert.match(result.stderr, /releases/);
  } finally {
    server.close();
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
