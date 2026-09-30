#!/usr/bin/env bash
#
# 发布前置自检（在本地运行，确认平台侧配置是否齐备）
#
# 用途：在推 tag 或手动触发发布之前，快速确认「不是配置问题」，
#       避免把平台配置错误误判为构建失败。
#
# 用法：
#   ./scripts/preflight-release.sh              # 检查本地仓库状态
#   gh auth status && ./scripts/preflight-release.sh --remote
#
# 注意：Secret 的值无法读取（GitHub 也不允许），本脚本只能检查
#       「环境是否存在」「分支/tag 是否就绪」等可观测项。
#
set -uo pipefail

REPO_SLUG="${GOGOGO_REPO:-leebro7/GoneNoRun}"
FAIL=0
ok()   { echo "  ok    $1"; }
bad()  { echo "  FAIL  $1"; FAIL=$((FAIL+1)); }
warn() { echo "  warn  $1"; }

cd "$(dirname "$0")/.."

echo "发布前置自检 — ${REPO_SLUG}"
echo "============================================================"

# ---------------------------------------------------------------
# 1. 本地仓库状态
# ---------------------------------------------------------------
echo "[1] 本地仓库"

if [ -n "$(git status --porcelain)" ]; then
  bad "工作树有未提交改动（tag 应指向已提交的干净状态）"
else
  ok "工作树干净"
fi

VER=$(grep -oP "versionName\s+'\K[^']+" app/build.gradle 2>/dev/null | head -n1)
VC=$(grep -oP "versionCode\s+\K[0-9]+" app/build.gradle 2>/dev/null | head -n1)
if [ -n "${VER}" ]; then
  ok "versionName=${VER}  versionCode=${VC}"
else
  bad "无法解析 versionName"
fi

HEAD_SHA=$(git rev-parse HEAD)
ok "HEAD=${HEAD_SHA:0:8}"

# 与远端是否同步
if git rev-parse --verify -q github/main >/dev/null 2>&1; then
  if [ "$(git rev-parse HEAD)" = "$(git rev-parse github/main)" ]; then
    ok "本地 HEAD 与 github/main 同步"
  else
    bad "本地 HEAD 与 github/main 不一致，请先 push"
  fi
else
  warn "未配置 github remote 或未 fetch，跳过同步检查"
fi

# ---------------------------------------------------------------
# 2. 关键文件存在性（发布链路依赖）
# ---------------------------------------------------------------
echo "[2] 发布链路文件"

for f in \
  .github/workflows/build-release.yml \
  .github/actions/security-gate/action.yml \
  scripts/gen-provenance.mjs \
  scripts/generate-signing-key.sh \
  gradle/wrapper/gradle-wrapper.properties
do
  [ -f "$f" ] && ok "$f" || bad "缺失 $f"
done

# wrapper 哈希固定
if grep -q '^distributionSha256Sum=' gradle/wrapper/gradle-wrapper.properties; then
  ok "Gradle 发行版哈希已固定"
else
  bad "gradle-wrapper.properties 缺少 distributionSha256Sum"
fi

# ---------------------------------------------------------------
# 3. 无密钥材料入库
# ---------------------------------------------------------------
echo "[3] 密钥卫生"

if git ls-files | grep -qEi '\.(jks|keystore|p12|pfx|pem|key|der|bks|pepk)$|keystore\.properties'; then
  bad "版本控制中存在密钥类文件"
else
  ok "版本控制中无密钥类文件"
fi

if grep -qE "(storePassword|keyPassword)\s*['\"][^'\"]{2,}['\"]" app/build.gradle; then
  bad "build.gradle 中存在字面量签名口令"
else
  ok "build.gradle 无字面量签名口令"
fi

# ---------------------------------------------------------------
# 4. 本地校验器（与 CI 相同的检查）
# ---------------------------------------------------------------
echo "[4] 本地校验"

if command -v node >/dev/null 2>&1; then
  if node scripts/check-workflow-policy.mjs >/dev/null 2>&1; then
    ok "workflow 策略 R1–R7 通过"
  else
    bad "workflow 策略校验失败"
  fi
  if node scripts/check-workflow-policy.test.mjs >/dev/null 2>&1; then
    ok "策略校验器自测通过"
  else
    bad "策略校验器自测失败"
  fi
  if (cd app/libs && sha256sum -c VENDORED_DEPENDENCIES.sha256 >/dev/null 2>&1); then
    ok "vendored 依赖校验和通过"
  else
    bad "vendored 依赖校验和失败"
  fi
else
  warn "未安装 node，跳过本地校验"
fi

# ---------------------------------------------------------------
# 5. 远端平台配置（需要 gh 且已登录）
# ---------------------------------------------------------------
echo "[5] 远端平台配置"

if [ "${1:-}" = "--remote" ] && command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
  ENVS=$(gh api "repos/${REPO_SLUG}/environments" --jq '.environments[].name' 2>/dev/null || true)
  if printf '%s\n' "${ENVS}" | grep -qx 'production'; then
    ok "environment 'production' 存在"
    RULES=$(gh api "repos/${REPO_SLUG}/environments/production" --jq '.protection_rules | length' 2>/dev/null || echo 0)
    if [ "${RULES:-0}" -gt 0 ]; then
      ok "production 含 ${RULES} 条保护规则（含审批）"
    else
      warn "production 无保护规则 —— 发布不会等待人工审批"
    fi
  else
    bad "environment 'production' 不存在（发布作业无法取得签名 Secret）"
  fi

  echo "  -- Secret 名称（值不可读，请自行核对是否都已配置）"
  gh api "repos/${REPO_SLUG}/environments/production/secrets" \
     --jq '.secrets[].name' 2>/dev/null | sed 's/^/     /' \
     || warn "无法列出 environment secrets（需要 admin 权限）"

  for s in SIGNING_KEYSTORE_BASE64 SIGNING_KEYSTORE_PASSWORD SIGNING_KEY_PASSWORD \
           SIGNING_KEY_ALIAS MAPS_API_KEY MAPS_SAFE_CODE; do
    if gh api "repos/${REPO_SLUG}/environments/production/secrets" --jq '.secrets[].name' 2>/dev/null | grep -qx "$s"; then
      ok "secret ${s} 已配置"
    else
      bad "secret ${s} 缺失或在仓库级（必须在 production environment 下）"
    fi
  done
else
  warn "跳过（需加 --remote，且已安装并登录 gh）"
  echo "     期望的 environment secrets："
  for s in SIGNING_KEYSTORE_BASE64 SIGNING_KEYSTORE_PASSWORD SIGNING_KEY_PASSWORD \
           SIGNING_KEY_ALIAS MAPS_API_KEY MAPS_SAFE_CODE; do
    echo "       - ${s}"
  done
fi

echo "============================================================"
if [ "${FAIL}" -eq 0 ]; then
  echo "自检通过（0 项失败）"
else
  echo "自检发现 ${FAIL} 项失败 —— 请先修复再发布"
fi
exit $([ "${FAIL}" -eq 0 ] && echo 0 || echo 1)
