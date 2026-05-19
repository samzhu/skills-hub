import { test as teardown } from '@playwright/test';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const execFileAsync = promisify(execFile);

teardown('@S202: tear down fixture Compose stack', async () => {
  await execFileAsync('docker', [
    'compose',
    '-f',
    'compose.e2e.yaml',
    'down',
    '-v',
    '--remove-orphans',
  ], { cwd: process.cwd() });
});
