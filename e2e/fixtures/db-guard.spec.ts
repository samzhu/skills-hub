import { expect, test } from '@playwright/test';

import { assertE2eDatabase } from './db-guard';

test('@S202 @AC-S202-6: accepts only skillshub_e2e local DB', () => {
  expect(() => assertE2eDatabase({
    host: 'localhost',
    database: 'skillshub_e2e',
    activeProfiles: 'local',
  })).not.toThrow();
});

test('@S202 @AC-S202-6: refuses non-e2e database before destructive SQL', () => {
  expect(() => assertE2eDatabase({
    host: 'prod-db',
    database: 'skillshub',
    activeProfiles: 'prod',
  })).toThrow(/Refusing to reset non-e2e database/);
});
