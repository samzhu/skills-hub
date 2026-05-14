# S147-T05: Detail page security alignment

## Spec
S147 — 掃描器語意分析缺口研究

## BDD
Given the backend report API returns dynamic security categories and issue-code findings
When a user opens `SkillDetailPage` and switches to the security tab
Then the UI renders category cards from the API response instead of hard-coded `shell / paths / secrets / deps`
And findings show issue code, severity, file/line, evidence summary, and remediation
And `SecurityHeroCard` shows overall status plus the most severe category without hiding legacy reports

## Target Files
- `frontend/src/api/security.ts`
- `frontend/src/hooks/useSecurityReport.ts`
- `frontend/src/components/v2/SecurityHeroCard.tsx`
- `frontend/src/components/v2/SecurityHeroCard.test.tsx`
- `frontend/src/components/v2/tabs/SecurityTab.tsx`
- `frontend/src/components/v2/tabs/SecurityTab.test.tsx`
- `frontend/src/components/v2/SecurityAuditCard.tsx`
- `frontend/src/pages/SkillDetailPage.test.tsx`

## Depends On
- S147-T04 PASS

## Status
pending
