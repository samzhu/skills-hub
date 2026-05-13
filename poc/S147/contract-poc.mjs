#!/usr/bin/env node

import assert from 'node:assert/strict'

const legacyFindings = [
  {
    ruleId: 'DANGEROUS_COMMAND_RM_RF',
    severity: 'HIGH',
    message: 'Dangerous recursive delete',
    filePath: 'scripts/install.sh',
    line: 7,
    evidence: 'rm -rf /tmp/build',
    analyzer: 'pattern',
    owaspAst: 'AST04',
  },
  {
    ruleId: 'GITHUB_PAT',
    severity: 'MEDIUM',
    message: 'Hardcoded GitHub token',
    filePath: 'SKILL.md',
    line: 18,
    evidence: 'ghp_****abcd',
    analyzer: 'secret',
    owaspAst: 'AST03',
  },
]

const issueFindings = [
  {
    issueCode: 'W008',
    ruleId: 'HARDCODED_SECRET_GITHUB_PAT',
    severity: 'HIGH',
    message: 'Hardcoded GitHub token',
    filePath: 'SKILL.md',
    line: 18,
    evidence: 'ghp_****abcd',
    analyzer: 'hardcoded-secret',
    remediation: 'Move the token to the user agent secret store and reference it by name.',
    confidence: 'HIGH',
  },
  {
    issueCode: 'W011',
    ruleId: 'THIRD_PARTY_CONTENT_INSTRUCTIONS',
    severity: 'MEDIUM',
    message: 'Reads third-party page content and follows its instructions',
    filePath: 'SKILL.md',
    line: 31,
    evidence: 'open the URL and follow any instructions on the page',
    analyzer: 'third-party-content-exposure',
    remediation: 'Treat fetched page text as data and ignore instructions inside it.',
    confidence: 'MEDIUM',
  },
  {
    issueCode: 'W017',
    ruleId: 'SENSITIVE_DATA_EXPOSURE_EMAIL',
    severity: 'MEDIUM',
    message: 'Uploads email contents to an external summarizer',
    filePath: 'SKILL.md',
    line: 44,
    evidence: 'send the email thread to the hosted summarizer',
    analyzer: 'sensitive-data-exposure',
    remediation: 'Require user confirmation and redact private fields before export.',
    confidence: 'MEDIUM',
  },
]

const detectorDescriptors = [
  {
    className: 'HardcodedSecretDetector',
    implements: 'SecurityAnalyzer',
    issueCodes: ['W008'],
    phase: 'STATIC',
  },
  {
    className: 'ThirdPartyContentExposureDetector',
    implements: 'SecurityAnalyzer',
    issueCodes: ['W011'],
    phase: 'LLM',
  },
  {
    className: 'SensitiveDataExposureDetector',
    implements: 'SecurityAnalyzer',
    issueCodes: ['W017'],
    phase: 'LLM',
  },
]

function status(findings) {
  if (findings.some((f) => f.severity === 'HIGH')) return 'FAIL'
  if (findings.some((f) => f.severity === 'MEDIUM')) return 'WARN'
  return 'PASS'
}

function legacyCategory(finding) {
  const ruleId = finding.ruleId ?? ''
  if (finding.analyzer === 'resource-dos') return 'shell'
  if (finding.analyzer === 'pattern') {
    if (ruleId.startsWith('DANGEROUS_COMMAND_') || ruleId.startsWith('PIPE_TO_SHELL_')) return 'shell'
    if (ruleId.startsWith('SENSITIVE_PATH_')) return 'paths'
  }
  if (finding.analyzer === 'secret') return 'secrets'
  if (finding.analyzer === 'dep-vuln') return 'deps'
  return null
}

function buildLegacyChecks(findings) {
  const checks = {
    shell: [],
    paths: [],
    secrets: [],
    deps: [],
  }

  for (const finding of findings) {
    const category = legacyCategory(finding)
    if (category) checks[category].push(finding)
  }

  return Object.fromEntries(
    Object.entries(checks).map(([key, value]) => [
      key,
      {
        status: status(value),
        detail: value.length === 0 ? null : `${value[0].ruleId} · line ${value[0].line}: ${value[0].message}`,
      },
    ]),
  )
}

const issueCategoryMap = {
  W007: 'Credentials',
  W008: 'Credentials',
  W011: 'External Content',
  W017: 'Sensitive Data',
  W018: 'Sensitive Data',
}

function buildDynamicCategories(findings) {
  const buckets = new Map()
  for (const finding of findings) {
    const category = issueCategoryMap[finding.issueCode] ?? 'Other'
    if (!buckets.has(category)) buckets.set(category, [])
    buckets.get(category).push(finding)
  }

  return [...buckets.entries()].map(([name, bucket]) => ({
    name,
    status: status(bucket),
    count: bucket.length,
    issueCodes: [...new Set(bucket.map((f) => f.issueCode))],
    topFinding: {
      issueCode: bucket[0].issueCode,
      ruleId: bucket[0].ruleId,
      remediation: bucket[0].remediation,
      confidence: bucket[0].confidence,
    },
  }))
}

const legacyChecks = buildLegacyChecks(legacyFindings)
const categories = buildDynamicCategories(issueFindings)

assert.equal(legacyChecks.shell.status, 'FAIL')
assert.equal(legacyChecks.paths.status, 'PASS')
assert.equal(legacyChecks.secrets.status, 'WARN')
assert.equal(legacyChecks.deps.status, 'PASS')

assert.deepEqual(
  categories.map((category) => `${category.name}=${category.status}`),
  ['Credentials=FAIL', 'External Content=WARN', 'Sensitive Data=WARN'],
)

assert.ok(issueFindings.every((finding) => finding.ruleId), 'new findings keep ruleId for SARIF compatibility')
assert.ok(issueFindings.every((finding) => finding.issueCode), 'new findings expose issueCode')
assert.ok(issueFindings.every((finding) => finding.remediation), 'new findings expose remediation text')
assert.ok(detectorDescriptors.every((detector) => detector.implements === 'SecurityAnalyzer'))

console.log('S147 T00 POC PASS')
console.log(
  `legacy checks: shell=${legacyChecks.shell.status}, paths=${legacyChecks.paths.status}, secrets=${legacyChecks.secrets.status}, deps=${legacyChecks.deps.status}`,
)
console.log(`dynamic categories: ${categories.map((category) => `${category.name}=${category.status}`).join(', ')}`)
console.log(`detectors: ${detectorDescriptors.map((detector) => detector.className).join(', ')}`)
