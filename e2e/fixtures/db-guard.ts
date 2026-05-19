const ALLOWED_HOSTS = new Set(['localhost', '127.0.0.1', 'db']);
const E2E_DATABASE = 'skillshub_e2e';
const E2E_PROFILE = 'local';

export type E2eDatabaseTarget = {
  host: string;
  database: string;
  activeProfiles: string;
};

export function assertE2eDatabase(target: E2eDatabaseTarget): void {
  const host = target.host.trim().toLowerCase();
  const database = target.database.trim();
  const activeProfiles = target.activeProfiles.trim();

  if (database !== E2E_DATABASE) {
    throw new Error(`Refusing to reset non-e2e database: database=${database}`);
  }
  if (!ALLOWED_HOSTS.has(host)) {
    throw new Error(`Refusing to reset non-e2e database: host=${target.host}`);
  }
  if (activeProfiles !== E2E_PROFILE) {
    throw new Error(`Refusing to reset non-e2e database: activeProfiles=${activeProfiles}`);
  }
}
