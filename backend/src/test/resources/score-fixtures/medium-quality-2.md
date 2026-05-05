---
name: npm-dependency-updater
description: Updates npm packages in a JavaScript or TypeScript project. Use when packages are outdated or have security vulnerabilities. Runs npm audit and updates dependencies.
allowed-tools:
  - Bash(npm:*)
  - Read
  - Edit
---
# NPM Dependency Updater

Updates npm packages and fixes security vulnerabilities in JavaScript/TypeScript projects.

## Steps

1. Run `npm audit` to see vulnerabilities
2. Run `npm outdated` to see outdated packages
3. Update safe minor/patch versions: `npm update`
4. For major version updates, check the changelog before updating
5. Run `npm audit fix` to fix security issues automatically
6. Run tests to verify nothing broke: `npm test`

## Notes

Be careful with major version updates as they may contain breaking changes.
Always check the changelog before updating major versions.

Some packages like React and TypeScript need careful consideration before updating.
The user should review the changes before committing.
