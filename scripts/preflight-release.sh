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
    ok "workflow 策略 R1–R8 通过"
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
# 5. 远端平台配置
#
# 权限最小化设计：只用**公开、无需认证**的 GitHub API。
# 早期版本依赖 `gh` 且需要 admin 才能读 environment/secrets —— 那等于要求
# 运维为自己授予过宽权限。改为观察最近一次 Build Release 的**步骤级结论**：
# 若 "Materialize signing keystore" 成功，说明签名 Secret 就位；失败即缺失。
# 这不需要任何凭据。
# ---------------------------------------------------------------
echo "[5] 远端平台配置（公开 API，无需认证）"

API="https://api.github.com/repos/${REPO_SLUG}"
fetch_json() { timeout 30 curl -sS -H 'Accept: application/vnd.github+json' "$1" 2>/dev/null; }

ENV_JSON=$(fetch_json "${API}/environments")
if printf '%s' "${ENV_JSON}" | grep -q '"name"'; then
  if printf '%s' "${ENV_JSON}" | grep -q '"production"'; then
    ok "environment 'production' 存在"
  else
    bad "environment 'production' 不存在 —— 发布作业无法取得签名 Secret"
  fi
  RULES=$(printf '%s' "${ENV_JSON}" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{try{const j=JSON.parse(s);const e=(j.environments||[]).find(x=>x.name==="production");console.log(e?((e.protection_rules||[]).length):"-1")}catch(_){console.log("-1")}})' 2>/dev/null)
  if [ "${RULES}" = "-1" ]; then
    warn "无法读取 production 保护规则（需 admin，属预期）"
  elif [ "${RULES}" = "0" ]; then
    warn "production 保护规则为 0 —— 发布不会等待人工审批"
  else
    ok "production 含 ${RULES} 条保护规则"
  fi
else
  warn "environments 端点不可读（可能未启用 Environments）"
fi

# 最近一次 Build Release 的步骤级结论 —— 无需任何凭据
RUNS=$(fetch_json "${API}/actions/workflows/build-release.yml/runs?per_page=1")
RUN_ID=$(printf '%s' "${RUNS}" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{try{const j=JSON.parse(s);const r=(j.workflow_runs||[])[0];console.log(r?r.id:"")}catch(_){console.log("")}})' 2>/dev/null)
if [ -n "${RUN_ID}" ]; then
  CONC=$(printf '%s' "${RUNS}" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{try{const j=JSON.parse(s);const r=(j.workflow_runs||[])[0];console.log((r.conclusion||r.status)+" / "+(r.head_branch||""))}catch(_){console.log("?")}})' 2>/dev/null)
  echo "  -- 最近一次 Build Release: ${CONC}"

  fetch_json "${API}/actions/runs/${RUN_ID}/jobs" | node -e '
let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{
  try{
    const j=JSON.parse(s);
    const job=(j.jobs||[]).find(x=>x.name&&x.name.indexOf("Build")===0)||(j.jobs||[])[0];
    if(!job) return;
    const mat=(job.steps||[]).find(st=>/Materialize signing keystore/.test(st.name));
    const fail=(job.steps||[]).find(st=>st.conclusion==="failure");
    if(mat&&mat.conclusion==="success") console.log("  ok    signing secrets 就位（Materialize signing keystore 成功）");
    else if(mat&&mat.conclusion==="failure") console.log("  FAIL  签名 Secret 缺失，或未放在 production environment 下");
    else if(mat&&mat.conclusion==="skipped") console.log("  warn  签名步骤被跳过（上游步骤先失败）");
    if(fail) console.log("  --    首个失败步骤: "+(fail.number||"")+" "+fail.name);
  }catch(e){}
})' 2>/dev/null
else
  warn "尚无 Build Release 运行记录"
fi

echo "  -- 期望的 environment secrets（值不可读，仅核对名称）："
for s in SIGNING_KEYSTORE_BASE64 SIGNING_KEYSTORE_PASSWORD SIGNING_KEY_PASSWORD \
         SIGNING_KEY_ALIAS MAPS_API_KEY MAPS_SAFE_CODE; do
  echo "       - ${s}"
done

echo "============================================================"
if [ "${FAIL}" -eq 0 ]; then
  echo "自检通过（0 项失败）"
else
  echo "自检发现 ${FAIL} 项失败 —— 请先修复再发布"
fi
exit $([ "${FAIL}" -eq 0 ] && echo 0 || echo 1)
