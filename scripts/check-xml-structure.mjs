#!/usr/bin/env node
/**
 * XML 结构校验（无 xmllint 时的替代品）
 *
 * 动机：一次 manifest 编辑引入了**嵌套注释**，而 XML 不允许注释嵌套，
 * 导致 aapt2 在 CI 上失败：
 *
 *     <!--
 *     <!--  ... -->
 *     （外层缺少 -->）
 *
 * 本环境没有 xmllint，且当时的门禁只检查权限关键词，不检查 XML 结构，
 * 因此这个错误只在 CI 编译时才暴露。本脚本把该检查左移。
 *
 * 检查项：
 *   1. 注释必须配平，且不得嵌套（XML 禁止）
 *   2. 注释内不得出现 "--"（XML 禁止）
 *   3. 标签必须成对（按栈匹配），空元素（<x ... />）单独处理
 *   4. 属性引号必须成对
 *
 * 局限：这不是完整 XML 解析器（不处理 CDATA、DOCTYPE、属性内实体等）。
 * 它覆盖的是实际踩过的坑，而不是取代 aapt2。
 *
 * 用法：node scripts/check-xml-structure.mjs
 */

import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const ROOTS = ['app/src/main', 'app/src/test', 'app/src/androidTest'];
const SELF_CLOSING_OK = new Set(['br', 'hr', 'img', 'input', 'meta', 'link']);

function listXml(dir, acc = []) {
  if (!existsSync(dir)) return acc;
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    const st = statSync(p);
    if (st.isDirectory()) listXml(p, acc);
    else if (e.endsWith('.xml')) acc.push(p);
  }
  return acc;
}

const files = ROOTS.flatMap((r) => listXml(r));
const problems = [];

for (const file of files) {
  const src = readFileSync(file, 'utf8');
  const lines = src.split('\n');

  // --- 1) 注释配平与嵌套 ------------------------------------------------
  let depth = 0;
  let maxDepth = 0;
  let inCommentStartLine = 0;
  lines.forEach((l, i) => {
    const opens = (l.match(/<!--/g) || []).length;
    const closes = (l.match(/-->/g) || []).length;
    if (opens && depth === 0) inCommentStartLine = i + 1;
    if (opens && depth > 0) {
      problems.push({
        file, line: i + 1,
        msg: `注释嵌套：第 ${inCommentStartLine} 行的注释尚未闭合又出现 <!--（XML 不允许嵌套注释）`,
      });
    }
    depth += opens - closes;
    maxDepth = Math.max(maxDepth, depth);
  });
  if (depth !== 0) {
    problems.push({
      file, line: inCommentStartLine || 1,
      msg: `注释未配平：从第 ${inCommentStartLine} 行开始的注释缺少 ${depth} 个 "-->"`,
    });
  }

  // --- 2) 注释内出现 "--" ------------------------------------------------
  let inC = false;
  lines.forEach((l, i) => {
    let rest = l;
    if (!inC) {
      const o = rest.indexOf('<!--');
      if (o >= 0) { inC = true; rest = rest.slice(o + 4); }
    }
    if (inC) {
      const c = rest.indexOf('-->');
      const body = c >= 0 ? rest.slice(0, c) : rest;
      if (body.includes('--')) {
        problems.push({ file, line: i + 1, msg: '注释体内出现 "--"（XML 非法）' });
      }
      if (c >= 0) inC = false;
    }
  });

  // --- 3) 标签成对 -------------------------------------------------------
  // 先移除注释，避免注释中的标签干扰
  const noComment = src.replace(/<!--[\s\S]*?-->/g, (m) => m.replace(/[^\n]/g, ' '));
  const stack = [];
  let line = 1;
  let k = 0;
  while (k < noComment.length) {
    const ch = noComment[k];
    if (ch === '\n') { line++; k++; continue; }
    if (ch !== '<') { k++; continue; }

    // 找到标签结束（考虑属性中的引号）
    let j = k + 1;
    let quote = null;
    while (j < noComment.length) {
      const c2 = noComment[j];
      if (c2 === '\n') line++;
      if (quote) { if (c2 === quote) quote = null; }
      else if (c2 === '"' || c2 === "'") quote = c2;
      else if (c2 === '>') break;
      j++;
    }
    const tag = noComment.slice(k, j + 1);
    k = j + 1;

    if (/^<\?/.test(tag) || /^<!/.test(tag)) continue;      // 声明 / DOCTYPE
    if (/\/>\s*$/.test(tag)) continue;                       // 自闭合
    const m = tag.match(/^<\/?\s*([A-Za-z_][\w.:-]*)/);
    if (!m) continue;
    const name = m[1];
    const closing = /^<\//.test(tag);
    if (closing) {
      const top = stack.pop();
      if (!top || top.name !== name) {
        problems.push({
          file, line,
          msg: `标签不匹配：</${name}> 对应的开始标签是 ${top ? `<${top.name}>（第 ${top.line} 行）` : '（栈为空）'}`,
        });
      }
    } else {
      stack.push({ name, line });
    }
    // 属性引号配平
    const quotes = (tag.match(/"/g) || []).length;
    if (quotes % 2 !== 0) {
      problems.push({ file, line, msg: `属性引号不成对：${tag.slice(0, 60)}` });
    }
  }
  for (const t of stack) {
    problems.push({ file, line: t.line, msg: `标签 <${t.name}> 未闭合` });
  }
}

console.log('XML 结构校验');
console.log('='.repeat(64));
console.log(`  已扫描 ${files.length} 个 XML 文件`);

if (problems.length === 0) {
  console.log('通过：未发现注释嵌套/未配平、标签不匹配或引号问题');
  process.exit(0);
}

console.log(`\n发现 ${problems.length} 处问题：\n`);
for (const p of problems) {
  console.log(`  ${p.file}:${p.line}`);
  console.log(`        ${p.msg}`);
}
process.exit(1);
