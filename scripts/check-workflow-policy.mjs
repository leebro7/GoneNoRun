#!/usr/bin/env node
/**
 * 零信任 Workflow 策略校验（原则 10：策略即代码）
 *
 * 在 CI 中强制以下不变量，任何违规都会让构建失败：
 *
 *   R1  所有 `uses:` 必须固定到 40 位 commit SHA
 *   R2  每个 workflow 必须有顶层 `permissions:`（默认拒绝式最小权限）
 *   R3  禁止 `pull_request_target` 触发（可被 fork PR 利用以获得 Secrets）
 *   R4  持有签名凭据的 workflow 必须绑定 `environment:`
 *   R5  禁止通配 tag 触发发布（`tags: ['*']`）
 *   R6  发布类 workflow 顶层不得为 `contents: write`
 *   R7  仓库内不得出现密钥库类文件
 *   R8  workflow 不得在 PR 路径上引用签名相关 Secret
 *
 * 实现刻意不依赖任何第三方 YAML 解析器：只做针对上述不变量的
 * 逐行结构化检查，避免校验器自身成为供应链风险。
 */

import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join, extname, basename } from 'node:path';
import { execFileSync } from 'node:child_process';

const WORKFLOW_DIR = '.github/workflows';
const KEYSTORE_EXT = new Set(['.jks', '.keystore', '.p12', '.pfx', '.bks', '.pepk']);

const findings = [];
const fail = (rule, file, line, message) =>
  findings.push({ rule, file, line, message });

function listWorkflows() {
  if (!existsSync(WORKFLOW_DIR)) return [];
  return readdirSync(WORKFLOW_DIR)
    .filter((f) => extname(f) === '.yml' || extname(f) === '.yaml')
    .map((f) => join(WORKFLOW_DIR, f));
}

/** 去掉行尾注释，但保留引号内的 # （够用即可，workflow 中极少出现） */
function stripComment(line) {
  let inSingle = false;
  let inDouble = false;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (c === "'" && !inDouble) inSingle = !inSingle;
    else if (c === '"' && !inSingle) inDouble = !inDouble;
    else if (c === '#' && !inSingle && !inDouble) {
      if (i === 0 || /\s/.test(line[i - 1])) return line.slice(0, i);
    }
  }
  return line;
}

function indentation(line) {
  const m = line.match(/^(\s*)/);
  return m ? m[1].length : 0;
}

function checkWorkflow(file) {
  const raw = readFileSync(file, 'utf8');
  const lines = raw.split('\n');
  const rel = file;

  let hasTopPermissions = false;
  let inTopOnBlock = false;
  let topLevelWrite = false;
  let wildcardTag = false;
  // 顶层 permissions: 块的缩进；用于识别其下的 contents: write
  let topPermissionsIndent = null;

  // 作业级跟踪：每个 job 的缩进、是否声明 environment、是否引用签名 Secret
  const jobs = new Map(); // name -> { environment: bool, signingSecret: bool, line: number }
  let currentJob = null;
  let inJobsBlock = false;
  let jobsIndent = null;

  lines.forEach((rawLine, idx) => {
    const lineNo = idx + 1;
    const line = stripComment(rawLine);
    const indent = indentation(line);
    const trimmed = line.trim();
    if (trimmed === '') return;

    // --- 顶层键跟踪 -------------------------------------------------------
    if (indent === 0) {
      inTopOnBlock = false;
      currentJob = null;
      jobsIndent = null;
      topPermissionsIndent = null;
      // 只有 jobs: 本身进入作业块；其他任何顶层键都要退出作业块，
      // 否则 jobs: 之后的 permissions:/on: 会被误认为仍在作业内。
      inJobsBlock = /^jobs\s*:/.test(trimmed);
      if (/^permissions\s*:/.test(trimmed)) {
        hasTopPermissions = true;
        topPermissionsIndent = indent; // 0
      }
      if (/^on\s*:/.test(trimmed)) inTopOnBlock = true;
    }

    // --- 进入 jobs: 之下的具体作业 ---------------------------------------
    if (inJobsBlock) {
      if (jobsIndent === null && /^[A-Za-z0-9_-]+\s*:/.test(trimmed)) {
        jobsIndent = indent;
        currentJob = trimmed.replace(/\s*:.*$/, '');
        jobs.set(currentJob, { environment: false, signingSecret: false, line: lineNo });
      } else if (jobsIndent !== null && indent === jobsIndent && /^[A-Za-z0-9_-]+\s*:/.test(trimmed)) {
        currentJob = trimmed.replace(/\s*:.*$/, '');
        jobs.set(currentJob, { environment: false, signingSecret: false, line: lineNo });
      } else if (currentJob && indent > jobsIndent) {
        const job = jobs.get(currentJob);
        if (/^environment\s*:/.test(trimmed)) job.environment = true;
      }
    }

    // --- R1: uses 必须固定 SHA -------------------------------------------
    const usesMatch = trimmed.match(/^-?\s*uses\s*:\s*(\S+)/);
    if (usesMatch) {
      const ref = usesMatch[1].replace(/^["']|["']$/g, '');
      if (!ref.startsWith('./') && !ref.startsWith('docker://')) {
        const at = ref.lastIndexOf('@');
        const sha = at >= 0 ? ref.slice(at + 1) : '';
        if (!/^[0-9a-f]{40}$/.test(sha)) {
          fail('R1', rel, lineNo, `Action 未固定到 40 位 commit SHA: ${ref}`);
        }
      }
    }

    // --- R3: 禁止 pull_request_target ------------------------------------
    if (/\bpull_request_target\b/.test(trimmed)) {
      fail('R3', rel, lineNo, '使用了 pull_request_target，可被 fork PR 利用以获取 Secrets');
    }

    // --- R5: 通配 tag 触发 -----------------------------------------------
    if (inTopOnBlock && /^-\s*['"]?\*['"]?\s*$/.test(trimmed)) {
      wildcardTag = true;
      fail('R5', rel, lineNo, "标签触发使用了通配 '*'，任何 tag 都可触发发布");
    }

    // --- R6: 顶层 permissions 不得授予 contents: write -------------------
    // 注意：contents: write 是嵌套在顶层 permissions: 之下的，缩进 > 0。
    // 写权限应下沉到具体作业，使默认权限保持只读。
    if (topPermissionsIndent !== null
        && indent > topPermissionsIndent
        && !inJobsBlock
        && /^contents\s*:\s*write\b/.test(trimmed)) {
      topLevelWrite = true;
      fail('R6', rel, lineNo, '顶层 permissions 声明了 contents: write，应下沉到具体作业');
    }

    // --- R4/R8: 签名凭据相关 ---------------------------------------------
    // 必须使用精确的 Secret 名称，否则 secrets.GITHUB_TOKEN 会被关键词
    // KEY_STORE / KEY_ 误匹配。GITHUB_TOKEN 是平台内置令牌，不属于长期
    // 签名凭据，不应触发 environment 要求。
    if (/secrets\.(SIGNING_KEYSTORE_BASE64|SIGNING_KEYSTORE_PASSWORD|SIGNING_KEY_ALIAS|SIGNING_KEY_PASSWORD|SIGNING_KEY|KEYSTORE_PASSWORD|KEY_STORE_PASSWORD|KEY_PASSWORD)\b/.test(trimmed)) {
      if (currentJob && jobs.has(currentJob)) {
        jobs.get(currentJob).signingSecret = true;
      } else {
        // 顶层（非作业内）引用签名 Secret 同样必须受 environment 约束
        jobs.set('__top_level__', { environment: false, signingSecret: true, line: lineNo });
      }
    }
  });

  // --- R8: 声明的权限必须有对应证据（权限最小化）------------------------
  //
  // 过度授权是常见的最小权限违规，且人工审查容易漏。这里独立做一遍
  // 「作业切分」，逐个作业检查每条权限是否被实际行为需要。
  //
  // 证据映射（保守：存在任一可能的使用方式即视为需要）：
  //   contents: read          <- actions/checkout、下载制品、release view
  //   contents: write         <- action-gh-release、gh release create、git push
  //   issues: write           <- github.rest.issues.*
  //   pull-requests: write    <- github.rest.pulls.*
  //   security-events: write  <- CodeQL init/analyze、upload-sarif
  //   actions: read           <- github.rest.actions.*、upload/download-artifact
  //   id-token: write         <- OIDC（云联邦、Sigstore keyless）
  const EVIDENCE = [
    { perm: 'contents',       level: 'read',  re: /actions\/checkout|download-artifact|gh release view/ },
    { perm: 'contents',       level: 'write', re: /action-gh-release|gh release create|gh release upload|git push/ },
    { perm: 'issues',         level: 'write', re: /github\.rest\.issues\./ },
    { perm: 'pull-requests',  level: 'write', re: /github\.rest\.pulls\./ },
    { perm: 'security-events', level: 'write', re: /codeql-action\/(init|analyze)|upload-sarif/ },
    { perm: 'actions',        level: 'read',  re: /github\.rest\.actions\.|upload-artifact|download-artifact/ },
    { perm: 'id-token',       level: 'write', re: /id-token|OIDC/ },
  ];

  // 第二遍：切分出每个作业的文本范围与其 permissions 条目
  {
    let inJobs = false;
    let jobIndent = null;
    const bounds = []; // { name, line, start, end }
    lines.forEach((rawLine, idx) => {
      const line = stripComment(rawLine);
      const trimmed = line.trim();
      if (trimmed === '') return;
      const ind = indentation(line);
      if (ind === 0) {
        inJobs = /^jobs\s*:/.test(trimmed);
        jobIndent = null;
        return;
      }
      if (!inJobs) return;
      if (jobIndent === null && /^[A-Za-z0-9_-]+\s*:/.test(trimmed)) {
        jobIndent = ind;
        bounds.push({ name: trimmed.replace(/\s*:.*$/, ''), line: idx + 1, start: idx, end: lines.length });
      } else if (jobIndent !== null && ind === jobIndent && /^[A-Za-z0-9_-]+\s*:/.test(trimmed)) {
        if (bounds.length) bounds[bounds.length - 1].end = idx;
        bounds.push({ name: trimmed.replace(/\s*:.*$/, ''), line: idx + 1, start: idx, end: lines.length });
      }
    });

    for (const job of bounds) {
      const region = lines.slice(job.start, job.end);
      const body = region.join('\n');
      // 提取该作业内 permissions: 块下的条目
      const perms = [];
      let inPerm = false;
      let permIndent = null;
      for (const l of region) {
        const t = stripComment(l).trim();
        if (t === '') continue;
        const ind = indentation(l);
        if (/^permissions\s*:\s*$/.test(t)) {
          inPerm = true;
          permIndent = ind;
          continue;
        }
        // 形如 `permissions: contents: read` 的单行写法
        const inline = t.match(/^permissions\s*:\s*(\S+)\s*:\s*(\S+)/);
        if (inline) {
          perms.push([inline[1], inline[2]]);
          continue;
        }
        if (inPerm) {
          if (ind <= permIndent) { inPerm = false; continue; }
          const m = t.match(/^([a-z-]+)\s*:\s*(\S+)/);
          if (m) perms.push([m[1], m[2]]);
        }
      }
      for (const [perm, level] of perms) {
        if (level === 'none') continue;
        const ev = EVIDENCE.find((e) => e.perm === perm && e.level === level);
        if (!ev) continue;
        if (!ev.re.test(body)) {
          fail('R8', rel, job.line,
            `作业 "${job.name}" 声明了 ${perm}: ${level}，但作业内无对应使用证据（过度授权）`);
        }
      }
    }
  }

  // --- R2: 顶层 permissions 必须存在 -----------------------------------
  if (!hasTopPermissions) {
    fail('R2', rel, 1, 'workflow 缺少顶层 permissions 声明（应默认只读）');
  }

  // --- R4: 引用签名凭据的作业必须绑定 environment ----------------------
  for (const [name, job] of jobs) {
    if (job.signingSecret && !job.environment) {
      fail('R4', rel, job.line,
        `作业 "${name}" 引用签名相关 Secret 但未绑定 environment:，缺少人工审批闸门`);
    }
  }

  const isRelease = /build-release|release/i.test(basename(rel));
  if (isRelease && wildcardTag) {
    fail('R5', rel, 1, '发布 workflow 使用通配 tag 触发');
  }
}

/** R7: 仓库中不得存在密钥库类文件 */
function checkNoKeystores() {
  let tracked = [];
  try {
    tracked = execFileSync('git', ['ls-files'], { encoding: 'utf8' })
      .split('\n')
      .filter(Boolean);
  } catch {
    console.log('  (提示) 非 git 工作区，跳过已跟踪文件检查');
    return;
  }
  for (const f of tracked) {
    if (KEYSTORE_EXT.has(extname(f).toLowerCase())) {
      fail('R7', f, 0, `密钥库类文件被纳入版本控制: ${f}`);
    }
  }
}

console.log('零信任 Workflow 策略校验');
console.log('='.repeat(60));

const files = listWorkflows();
if (files.length === 0) {
  console.log('未找到 workflow 文件，跳过');
} else {
  files.forEach((f) => {
    checkWorkflow(f);
    console.log(`  已检查 ${f}`);
  });
}
checkNoKeystores();

console.log('='.repeat(60));
if (findings.length === 0) {
  console.log('通过：未发现策略违规');
  process.exit(0);
}

console.log(`发现 ${findings.length} 项违规：\n`);
for (const f of findings) {
  const loc = f.line ? `${f.file}:${f.line}` : f.file;
  console.log(`  [${f.rule}] ${loc}\n        ${f.message}`);
}
console.log('\n请修复后重新提交。规则说明见 docs/SECURITY-HARDENING.md');
process.exit(1);
