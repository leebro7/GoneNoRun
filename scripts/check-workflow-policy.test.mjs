#!/usr/bin/env node
/**
 * 策略校验器的对抗性测试（negative tests）
 *
 * 校验器的价值取决于它能否真的拦住违规。本脚本构造若干「应当失败」的
 * workflow 片段，确认 check-workflow-policy.mjs 对每一类违规都报错；
 * 同时构造一个「应当通过」的样例，确认不会误报。
 *
 * 用法：node scripts/check-workflow-policy.test.mjs
 */

import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const CHECKER = join(here, 'check-workflow-policy.mjs');

/** 在临时目录中放入一个 workflow，运行校验器，返回是否通过 */
function runChecker(workflowYaml) {
  const dir = mkdtempSync(join(tmpdir(), 'policy-test-'));
  try {
    mkdirSync(join(dir, '.github', 'workflows'), { recursive: true });
    writeFileSync(join(dir, '.github', 'workflows', 'test.yml'), workflowYaml);
    // 校验器以 cwd 为仓库根
    execFileSync(process.execPath, [CHECKER], { cwd: dir, stdio: 'pipe' });
    return { passed: true, output: '' };
  } catch (e) {
    return { passed: false, output: `${e.stdout || ''}${e.stderr || ''}` };
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

const cases = [
  {
    name: 'R1 未固定 SHA 的 Action 必须被拦下',
    expectPass: false,
    expectRule: 'R1',
    yaml: `name: t
on: [push]
permissions:
  contents: read
jobs:
  a:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v5
`,
  },
  {
    name: 'R1 固定到 40 位 SHA 应通过',
    expectPass: true,
    yaml: `name: t
on: [push]
permissions:
  contents: read
jobs:
  a:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@08c6903cd8c0fde910a37f88322edcfb5dd907a8
`,
  },
  {
    name: 'R2 缺少顶层 permissions 必须被拦下',
    expectPass: false,
    expectRule: 'R2',
    yaml: `name: t
on: [push]
jobs:
  a:
    runs-on: ubuntu-latest
    steps:
      - run: echo hi
`,
  },
  {
    name: 'R3 pull_request_target 必须被拦下',
    expectPass: false,
    expectRule: 'R3',
    yaml: `name: t
on:
  pull_request_target:
permissions:
  contents: read
jobs:
  a:
    runs-on: ubuntu-latest
    steps:
      - run: echo hi
`,
  },
  {
    name: 'R4 引用签名 Secret 但无 environment 必须被拦下',
    expectPass: false,
    expectRule: 'R4',
    yaml: `name: release
on: [push]
permissions:
  contents: read
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - run: echo "\${{ secrets.SIGNING_KEYSTORE_PASSWORD }}"
`,
  },
  {
    name: 'R4 绑定 environment 后应通过',
    expectPass: true,
    yaml: `name: release
on: [push]
permissions:
  contents: read
jobs:
  build:
    environment: production
    runs-on: ubuntu-latest
    steps:
      - run: echo "\${{ secrets.SIGNING_KEYSTORE_PASSWORD }}"
`,
  },
  {
    name: 'R4 GITHUB_TOKEN 不应被误判为签名凭据',
    expectPass: true,
    yaml: `name: t
on: [push]
permissions:
  contents: read
jobs:
  a:
    runs-on: ubuntu-latest
    steps:
      - run: echo "\${{ secrets.GITHUB_TOKEN }}"
`,
  },
  {
    name: 'R5 通配 tag 触发必须被拦下',
    expectPass: false,
    expectRule: 'R5',
    yaml: `name: t
on:
  push:
    tags:
      - '*'
permissions:
  contents: read
jobs:
  a:
    runs-on: ubuntu-latest
    steps:
      - run: echo hi
`,
  },
  {
    name: 'R6 顶层 contents: write 必须被拦下',
    expectPass: false,
    expectRule: 'R6',
    yaml: `name: t
on: [push]
permissions:
  contents: write
jobs:
  a:
    runs-on: ubuntu-latest
    steps:
      - run: echo hi
`,
  },
  {
    name: 'R8 作业声明 pull-requests: write 却不用 PR API 必须被拦下',
    expectPass: false,
    expectRule: 'R8',
    yaml: `name: t
on: [push]
permissions:
  contents: read
jobs:
  a:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      pull-requests: write
    steps:
      - run: echo hi
`,
  },
  {
    name: 'R8 作业声明 actions: read 却不用 actions API/artifact 必须被拦下',
    expectPass: false,
    expectRule: 'R8',
    yaml: `name: t
on: [push]
permissions:
  contents: read
jobs:
  a:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      actions: read
    steps:
      - run: echo hi
`,
  },
  {
    name: 'R8 有 checkout 时 contents: read 应通过',
    expectPass: true,
    yaml: `name: t
on: [push]
permissions:
  contents: read
jobs:
  a:
    runs-on: ubuntu-latest
    permissions:
      contents: read
    steps:
      - uses: actions/checkout@08c6903cd8c0fde910a37f88322edcfb5dd907a8
`,
  },
  {
    name: 'R8 用 issues API 时 issues: write 应通过',
    expectPass: true,
    yaml: `name: t
on: [push]
permissions:
  contents: none
jobs:
  a:
    runs-on: ubuntu-latest
    permissions:
      issues: write
    steps:
      - run: echo "github.rest.issues.createComment"
`,
  },
  {
    name: 'R8 声明 issues: write 却完全不用 issues API 必须被拦下',
    expectPass: false,
    expectRule: 'R8',
    yaml: `name: t
on: [push]
permissions:
  contents: none
jobs:
  a:
    runs-on: ubuntu-latest
    permissions:
      issues: write
    steps:
      - run: echo hi
`,
  },
];

let failed = 0;
console.log('策略校验器对抗性测试');
console.log('='.repeat(64));

for (const c of cases) {
  const r = runChecker(c.yaml);
  const ok = r.passed === c.expectPass;
  // 对预期失败的用例，额外确认报出了正确的规则编号
  const ruleOk = c.expectPass || !c.expectRule || r.output.includes(`[${c.expectRule}]`);
  const verdict = ok && ruleOk ? 'ok  ' : 'FAIL';
  if (!(ok && ruleOk)) failed++;
  console.log(`  ${verdict} ${c.name}`);
  if (!(ok && ruleOk)) {
    console.log(`       期望 ${c.expectPass ? '通过' : `失败(${c.expectRule})`}，实际 ${r.passed ? '通过' : '失败'}`);
    if (r.output) console.log(`       输出: ${r.output.trim().split('\n').slice(-4).join('\n              ')}`);
  }
}

console.log('='.repeat(64));
if (failed === 0) {
  console.log(`通过：${cases.length}/${cases.length} 个用例符合预期`);
  process.exit(0);
}
console.log(`${failed}/${cases.length} 个用例失败`);
process.exit(1);
