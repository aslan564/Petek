// Hosts the shared Chromium of one Pətək run (see PlaywrightBrowserEngine).
//
// This is what Playwright's hidden `launch-server` CLI command does, written against the public
// `BrowserType.launchServer` API of the playwright-core package bundled in the Playwright jar, plus a
// parent-death watchdog: the JVM holds our stdin open, so when the JVM goes away for any reason (even SIGKILL)
// stdin reaches EOF and the browser is closed instead of being orphaned.
//
// argv: <playwright-core package dir> <launchServer options as JSON>
// stdout: exactly one line `PETEK_WS_ENDPOINT=<ws url>` once the server accepts connections.
'use strict';

const [packageDir, optionsJson] = process.argv.slice(1);
const playwright = require(packageDir);
const options = JSON.parse(optionsJson);

const serverPromise = playwright.chromium.launchServer(options);
let closing = false;

function shutdown() {
  if (closing) return;
  closing = true;
  serverPromise
    .then((server) => server.close())
    .catch(() => {})
    .finally(() => process.exit(0));
}

process.stdin.on('end', shutdown);
process.stdin.on('error', shutdown);
process.stdin.resume();
process.on('SIGTERM', shutdown);

serverPromise.then(
  (server) => {
    if (!closing) process.stdout.write('PETEK_WS_ENDPOINT=' + server.wsEndpoint() + '\n');
  },
  (error) => {
    // The message says what went wrong (missing executable, missing libraries, …); the Node.js stack does not.
    process.stderr.write(String((error && error.message) || error) + '\n');
    process.exit(1);
  },
);
