#!/usr/bin/env node
/**
 * 未导入符号检查（在没有编译器时的替代品）
 *
 * 动机：本项目多次因为「用了某个类却没有 import」而编译失败，例如：
 *
 *   MainActivity.java:1171: error: package Build does not exist
 *     if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
 *
 * 本环境没有 JDK，无法 javac，因此用静态扫描捕获这一类错误：
 * 扫描 `Xxx.yyy` 形式的使用，若 Xxx 首字母大写，则它要么
 *   1) 已 import，
 *   2) 与本文件同包（同目录下的 .java），
 *   3) 在 java.lang 中，
 *   4) 是本文件内声明的类/接口，
 *   5) 是常见的内嵌/生成类（R、BuildConfig、各 View 内部类等，按包内类处理）。
 * 否则报错。
 *
 * 局限：这是启发式检查，不是编译器。它只覆盖「大写标识符 + 点号」这一形态，
 * 会漏掉方法不存在、签名不匹配等问题。它的价值在于把最常见的一类错误
 * 左移，而不是替代编译。
 *
 * 用法：node scripts/check-unimported-symbols.mjs
 */

import { readFileSync, readdirSync, existsSync, statSync } from 'node:fs';
import { join, basename } from 'node:path';

const SRC_ROOT = 'app/src/main/java';

const JAVA_LANG = new Set([
  'String', 'Object', 'System', 'Integer', 'Long', 'Double', 'Float', 'Boolean',
  'Byte', 'Short', 'Character', 'Math', 'Exception', 'RuntimeException', 'Error',
  'Throwable', 'Thread', 'Runnable', 'Class', 'Enum', 'Iterable', 'Comparable',
  'StringBuilder', 'StringBuffer', 'CharSequence', 'Number', 'Void', 'Process',
  'ProcessBuilder', 'Runtime', 'SecurityManager', 'StackTraceElement', 'Deprecated',
  'Override', 'SuppressWarnings', 'SafeVarargs', 'FunctionalInterface',
]);

/** 已知由构建生成或平台提供、无需 import 的名称 */
const KNOWN_UNIMPORTED = new Set([
  'R', 'BuildConfig',
]);

function listJavaFiles(dir, acc = []) {
  if (!existsSync(dir)) return acc;
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    const st = statSync(p);
    if (st.isDirectory()) listJavaFiles(p, acc);
    else if (e.endsWith('.java')) acc.push(p);
  }
  return acc;
}

/** 去掉注释与字符串字面量，避免误报 */
function stripCode(src) {
  let out = '';
  let i = 0;
  let st = 'code';
  while (i < src.length) {
    const c = src[i];
    const n = src[i + 1];
    if (st === 'code') {
      if (c === '/' && n === '/') { st = 'line'; i += 2; continue; }
      if (c === '/' && n === '*') { st = 'block'; i += 2; continue; }
      if (c === '"') { st = 'str'; i++; continue; }
      if (c === "'") { st = 'char'; i++; continue; }
      out += c; i++; continue;
    }
    if (st === 'line') { if (c === '\n') { st = 'code'; out += '\n'; } i++; continue; }
    if (st === 'block') { if (c === '*' && n === '/') { st = 'code'; i += 2; continue; } if (c === '\n') out += '\n'; i++; continue; }
    if (st === 'str') { if (c === '\\') { i += 2; continue; } if (c === '"') st = 'code'; if (c === '\n') out += '\n'; i++; continue; }
    if (st === 'char') { if (c === '\\') { i += 2; continue; } if (c === "'") st = 'code'; i++; continue; }
  }
  return out;
}

const files = listJavaFiles(SRC_ROOT);
if (files.length === 0) {
  console.log(`未在 ${SRC_ROOT} 找到 Java 源文件`);
  process.exit(0);
}

// 收集所有同包类名（按目录）
const classesByDir = new Map();
for (const f of files) {
  const dir = f.slice(0, f.lastIndexOf('/'));
  if (!classesByDir.has(dir)) classesByDir.set(dir, new Set());
  classesByDir.get(dir).add(basename(f, '.java'));
}

// 收集被声明或继承的常见 Android/第三方类名（这些必须 import，不豁免）
const findings = [];

for (const file of files) {
  const raw = readFileSync(file, 'utf8');
  const src = stripCode(raw);
  const dir = file.slice(0, file.lastIndexOf('/'));
  const samePkg = classesByDir.get(dir) || new Set();

  const imports = new Set();
  for (const m of src.matchAll(/^import\s+(?:static\s+)?([\w.]+)\s*;/gm)) {
    const fq = m[1];
    imports.add(fq);
    imports.add(fq.split('.').pop());
  }

  // 本文件内声明的类型
  const declared = new Set();
  for (const m of src.matchAll(/\b(?:class|interface|enum)\s+([A-Z]\w*)/g)) declared.add(m[1]);

  // 匹配**成员访问链的最外层标识符**：`Xxx.yyy` 或 `Xxx.yyy.zzz`
  // 只看链首，忽略 VERSION / VERSION_CODES / LayoutParams 这类「上一级的字段」。
  // 这正是最初那类错误（Build.VERSION.SDK_INT 未 import Build）的形态。
  const seen = new Map(); // name -> first line
  const lines = src.split('\n');
  lines.forEach((line, idx) => {
    // (?<![.\w]) 确保 Xxx 不是某个更长链的一部分（即它确实是链首）
    for (const m of line.matchAll(/(?<![.\w])([A-Z][A-Za-z0-9_]*)\./g)) {
      const name = m[1];
      if (seen.has(name)) continue;
      if (imports.has(name)) continue;
      if (samePkg.has(name)) continue;
      if (declared.has(name)) continue;
      if (JAVA_LANG.has(name)) continue;
      if (KNOWN_UNIMPORTED.has(name)) continue;
      // 排除全大写常量字段（如 ERRORNO.、REQUEST_）与已知的非类型链首
      if (/^[A-Z][A-Z0-9_]*$/.test(name)) continue;
      // 排除本文件中的局部变量/字段/参数。
      // 注意：必须用行首锚定 + 词边界，否则形如 `LocationMode` 的名称会让
      // 正则误匹配到内部的 `Mode`，从而把真正缺失的 import 也一并豁免
      // （这正是 Build 被漏报的原因）。
      const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      const declRe = new RegExp(
        `^\\s*(?:final\\s+)?[A-Z][\\w<>\\[\\],\\s.]*\\s+${escaped}\\s*(?:=|;|\\)|,)|` +
        `\\b${escaped}\\s*=\\s*new\\b`
      );
      if (declRe.test(src)) continue;
      seen.set(name, idx + 1);
    }
  });

  for (const [name, line] of seen) {
    findings.push({ file, line, name });
  }
}

console.log('未导入符号检查（启发式，替代编译器的一部分职能）');
console.log('='.repeat(64));
console.log(`  已扫描 ${files.length} 个 Java 文件`);

if (findings.length === 0) {
  console.log('通过：未发现「使用了但未导入」的大写符号');
  process.exit(0);
}

console.log(`\n发现 ${findings.length} 处可疑使用：\n`);
for (const f of findings) {
  console.log(`  ${f.file}:${f.line}`);
  console.log(`        使用了 ${f.name}.… 但既未 import，也非同包/java.lang/本文件声明`);
}
console.log('\n注意：这是启发式检查。若确为误报（例如通过泛型或平台内嵌类访问），');
console.log('请在本脚本的 KNOWN_UNIMPORTED 或豁免逻辑中说明原因，而不要直接关闭检查。');
process.exit(1);
