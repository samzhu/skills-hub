import { spawn } from 'node:child_process';

let shuttingDown = false;

function shutdown() {
  if (shuttingDown) return;
  shuttingDown = true;

  const child = spawn('docker', [
    'compose',
    '-f',
    'compose.e2e.yaml',
    'down',
    '-v',
    '--remove-orphans',
  ], { stdio: 'inherit' });

  const timeout = setTimeout(() => process.exit(1), 30_000);
  child.on('exit', code => {
    clearTimeout(timeout);
    process.exit(code ?? 0);
  });
}

process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);

console.log('Compose stack is ready; holding webServer process open for Playwright.');
setInterval(() => {}, 60_000);
