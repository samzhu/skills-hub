// S140 critical-path E2E — AC-3 (PRD P2 Upload + P3 Risk assessment).
//
// E2E profile sets `skillshub.security.oauth.enabled=false` →
// LabSecurityFilter 注入 `lab-user` with ROLE_admin，PublishPage 的
// `auth.status === 'authenticated'` 自動成立，無需 storageState 或 OAuth
// round-trip（spec §5 NOTE：S139 規劃 storageState；e2e LAB 模式更簡單）。
//
// Flow: /publish → submit → /publish/validate?id=X (poll riskLevel) →
// auto-redirect /publish/review?id=X when scan completes。e2e profile
// LlmJudge 不建立（無 genai api-key），但 PatternScanner / MetadataValidator
// 等 rule-based engines 仍跑，produce risk assessment（NONE for SKILL.md-only）。

import { test, expect, profiles } from './_fixtures';

test.describe('S140 — E2E Critical Path Backfill', () => {
  test('AC-3: 上傳合法 SKILL.md 並上架（含風險評估） @S140 @ac-3 @happy-path @profile-empty', async ({
    page,
    request,
  }) => {
    await test.step('Given empty platform state + authenticated lab user', async () => {
      await profiles.empty(request);
      // LAB mode auth gating: backend `/api/v1/me` returns lab-user immediately, useAuth status='authenticated'
    });

    await test.step('When user opens /publish, fills SKILL.md (text mode, low-risk content), submits', async () => {
      await page.goto('/publish');
      await expect(page.getByRole('heading', { level: 1, name: '發佈新技能' })).toBeVisible();

      // 切到 text mode（textarea 比 file upload 在 e2e 更穩）
      await page.getByRole('button', { name: '貼上文本' }).click();
      await page.getByLabel('技能名稱').fill('ac3-publish-helper');

      // 純 SKILL.md（無 scripts/）— 含必填 frontmatter name + description；
      // 觸發 backend security scanner happy path → riskLevel = NONE/LOW
      const skillMd = `---
name: ac3-publish-helper
description: AC-3 happy-path E2E 用 — 純 markdown skill，無 scripts，預期低風險上架。
license: MIT
---

# AC3 Publish Helper

This skill is used by S140 AC-3 happy-path verification.
Invoke when validating publish flow.
`;
      await page.getByPlaceholder(/name: my-skill/).fill(skillMd);
      // S188: platform version input starts blank; backend creates version label "1".
      await page.getByPlaceholder('DevOps').fill('Testing');
      // author 預填 lab-user（自動填入 useMe.sub）— 不動

      // 注意：不能用 /發[佈布]|提交|上傳/ 模糊比對 — 「上傳檔案」tab toggle 也會中。
      // 直接精確比對「發佈技能」（type=submit；PublishPage 的提交鈕）。
      await page.getByRole('button', { name: '發佈技能' }).click();
    });

    await test.step('Then redirect to validate → 自動 poll → review，顯示 v1 + 低風險 + 已上架', async () => {
      // PublishPage onSuccess 直接 navigate /publish/validate?id=X；
      // PublishValidatePage poll riskLevel until 設值 → /publish/review?id=X
      // (refetchInterval 2s，scanner 通常 < 5s 完成)。最終 URL 含 /publish/review
      await page.waitForURL(/\/publish\/(validate|review)\?id=/, { timeout: 30_000 });
      // wait for review page (final destination)
      await page.waitForURL(/\/publish\/review\?id=/, { timeout: 30_000 });

      // Risk badge — 必須精確匹配，避免命中 description 文字（"無 script" 等含相同字元組）。
      // RiskBadge 對 NONE 渲染 `<span data-slot="badge">無風險</span>`；用 exact text 鎖定 badge。
      await expect(page.getByText('無風險', { exact: true })).toBeVisible();

      // S188: blank publish version becomes platform version label v1.
      await expect(page.getByText('v1').first()).toBeVisible();

      // PublishReviewPage 顯示「上傳成功」/「已上架」/「上架」字眼之一
      await expect(page.getByText(/上[傳架]成功|已上架|上架/).first()).toBeVisible();
    });
  });
});
