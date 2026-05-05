---
name: test-runner
description: Runs tests for software projects. Use when the user wants to run unit tests, integration tests, or check test coverage. Works with popular testing frameworks.
allowed-tools:
  - Bash
---
# Test Runner

Runs tests and reports results for software projects.

## Usage

Run tests with the appropriate command for your project:
- Java/Maven: `mvn test`
- Java/Gradle: `./gradlew test`
- Node.js: `npm test`
- Python: `pytest`

## Checking results

Look at the test output to see which tests passed and failed.
If tests fail, check the error message for clues.

Run tests again after fixing issues to confirm the fix works.
