#!/usr/bin/env bash
#
# 提交前防线：阻止密钥材料进入版本控制（零信任原则 7/10）
#
# 安装（在仓库根执行一次）：
#   cp scripts/pre-commit-secret-guard.sh .git/hooks/pre-commit
#   chmod +x .git/hooks/pre-commit
#
# 作为纵深防御，CI 中仍会以 gitleaks 扫描全历史 —— 本地钩子可被 --no-verify
# 绕过，因此不能作为唯一控制。
#
set -euo pipefail

# 1) 文件名黑名单：任何被暂存的密钥类文件一律拒绝
BLOCKED=$(git diff --cached --name-only --diff-filter=ACM | \
  grep -Ei '\.(jks|keystore|p12|pfx|pem|key|der|bks|pepk)$|(^|/)keystore\.properties$|(^|/)\.signing/' || true)

if [ -n "${BLOCKED}" ]; then
  echo "提交被拒绝：检测到密钥类文件被暂存 ——" >&2
  echo "${BLOCKED}" | sed 's/^/  /' >&2
  echo >&2
  echo "这些文件绝不应进入版本控制。请执行：" >&2
  echo "  git rm --cached <file>    # 从索引移除（保留本地文件）" >&2
  echo "  并确认 .gitignore 已覆盖该类型" >&2
  exit 1
fi

# 2) 内容黑名单：构建脚本中不得出现硬编码签名口令
HARDCODED=$(git diff --cached -U0 -- '*.gradle' '*.gradle.kts' 'gradle.properties' 2>/dev/null | \
  grep -E '^\+' | grep -Ei "(storePassword|keyPassword)\s*['\"][^'\"]{3,}['\"]" || true)

if [ -n "${HARDCODED}" ]; then
  echo "提交被拒绝：构建脚本中出现硬编码签名口令 ——" >&2
  echo "${HARDCODED}" | sed 's/^/  /' >&2
  echo >&2
  echo "请改用环境变量或 keystore.properties（见 app/build.gradle 的 signingValue()）" >&2
  exit 1
fi

# 3) 若本地安装了 gitleaks，则增量扫描暂存内容
if command -v gitleaks >/dev/null 2>&1; then
  if ! gitleaks protect --staged --config .gitleaks.toml --redact --no-banner; then
    echo "提交被拒绝：gitleaks 在暂存内容中检测到疑似密钥" >&2
    exit 1
  fi
else
  echo "提示：未检测到 gitleaks，已跳过内容级扫描（CI 中仍会全历史扫描）" >&2
fi

exit 0
