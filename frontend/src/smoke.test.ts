import { describe, it, expect } from "vitest";

// Smoke baseline — 確保 vitest 在 frontend/ 至少找到 1 個 test 檔（避免 "No test files found, exit 1"）。
// 真正的 component / hook / api 測試由後續 frontend coverage spec 補。
describe("smoke", () => {
  it("vitest pipeline is wired", () => {
    expect(1 + 1).toBe(2);
  });
});
