import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import eslintConfigPrettier from 'eslint-config-prettier'
import tseslint from 'typescript-eslint'

export default tseslint.config(
  {
    // relayFonts 是 iconfont 平台生成的多文件产物，不参与源码规范检查
    ignores: ['dist', 'build', 'coverage', 'node_modules', '*.min.js', 'src/assets/relayFonts'],
  },
  {
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 2020,
      globals: {
        ...globals.browser,
      },
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'react-refresh/only-export-components': [
        'warn',
        { allowConstantExport: true },
      ],
    },
  },
  {
    // 构建脚本与配置文件（scripts/*.mjs、*.config.js 等）
    extends: [js.configs.recommended],
    files: ['**/*.{js,jsx,mjs,cjs}'],
    languageOptions: {
      ecmaVersion: 2020,
      globals: {
        ...globals.node,
      },
    },
  },
  {
    // 遗留代码里既有显式 any，逐文件登记而不是关闭全局规则
    files: [
      'src/components/ActionPanel/fileContentRender.test.ts',
      'src/components/ActionPanel/useMsgTypes.ts',
      'src/components/ActionView/workspaceFiles.test.ts',
      'src/components/ChatView/layout.test.tsx',
      'src/components/DataChat/Card.tsx',
      'src/components/DataChat/SimpleTable.tsx',
      'src/components/ai-elements/prompt-input/useSpeechRecognition.ts',
      'src/pages/FeaturedConversationDetail/view.test.tsx',
      'src/services/index.ts',
      'src/utils/askUserRestore.test.ts',
      'src/utils/chat.test.ts',
      'src/utils/conversationHistory.test.ts',
      'src/utils/taskArtifacts.ts',
      'src/utils/utils.ts',
    ],
    rules: {
      '@typescript-eslint/no-explicit-any': 'off',
    },
  },
  eslintConfigPrettier,
)
