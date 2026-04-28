import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

export default defineConfig([
  globalIgnores(['dist', 'coverage']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      globals: globals.browser,
    },
    // shadcn/ui CLI scaffolding 慣例：cva variants 與 component 同檔便於 `npx shadcn add` 升級不破壞拆檔結構。
    // react-refresh/only-export-components 規則對 cva CallExpression 不視為 constant
    // （allowConstantExport 不涵蓋；plugin source `constantExportExpressions` 只列 4 種 AST 型別 —
    // Literal / Unary / Template / Binary）。
    // allowExportNames hardcode 兩個目前用到的 cva 變數；plugin Issue #83 仍 open（無 regex 支援）。
    // 未來新增 cva 元件需擴充本 array。
    rules: {
      'react-refresh/only-export-components': [
        'warn',
        {
          // re-state — overriding `rules:` 完全替換 vite preset config
          allowConstantExport: true,
          allowExportNames: ['badgeVariants', 'tabsListVariants'],
        },
      ],
    },
  },
])
