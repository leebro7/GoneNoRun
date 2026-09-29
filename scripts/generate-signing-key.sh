#!/usr/bin/env bash
#
# 生成全新的 Android 发布签名身份（对应审计发现 F-01 的整改）
#
# 设计原则（第一性原理）：
#   * 私钥绝不进入版本控制，也绝不出现在脚本、日志或构建配置中。
#   * 密钥库口令由调用者提供（可交互输入或经环境变量），脚本不写入任何明文。
#   * 使用 PKCS#12 + PBES2/AES-256-CBC + SHA-256 MAC（现代 Android 构建工具兼容）。
#   * 同时提供证书指纹，用于发布记录与客户端校验比对。
#
# 用法：
#   ./scripts/generate-signing-key.sh                 # 交互式，输出到 .signing/（已 gitignore）
#   GOGOGO_KEYSTORE_PASSWORD='<强口令>' ./scripts/generate-signing-key.sh
#
# 生成后必须：
#   1. 将 .p12 以 base64 存为 GitHub Environment Secret: SIGNING_KEYSTORE_BASE64
#   2. 存入口令: SIGNING_KEYSTORE_PASSWORD / SIGNING_KEY_PASSWORD
#   3. 存入别名: SIGNING_KEY_ALIAS
#   4. 妥善离线备份密钥库与其口令（丢失将无法再发布可覆盖安装的更新）
#   5. 从本地磁盘删除明文密钥库
#
set -euo pipefail

OUT_DIR="${GOGOGO_SIGNING_DIR:-.signing}"
ALIAS="${GOGOGO_KEY_ALIAS:-gogogo-release}"
VALID_DAYS="${GOGOGO_KEY_VALID_DAYS:-10950}"   # 30 年
DNAME="${GOGOGO_KEY_DNAME:-/CN=GoGoGo_Release/O=GoGoGo/C=CN}"
KEY_BITS="${GOGOGO_KEY_BITS:-4096}"

command -v openssl >/dev/null 2>&1 || { echo "需要 openssl" >&2; exit 1; }

mkdir -p "${OUT_DIR}"
chmod 700 "${OUT_DIR}"

KEYSTORE="${OUT_DIR}/release.p12"
KEY_PEM="${OUT_DIR}/release.key.pem"
CERT_PEM="${OUT_DIR}/release.cert.pem"

# ---- 口令获取（不回显、不落盘） ------------------------------------------
if [ -z "${GOGOGO_KEYSTORE_PASSWORD:-}" ]; then
  read -r -s -p "请输入新的密钥库口令: " GOGOGO_KEYSTORE_PASSWORD; echo
  read -r -s -p "请再次输入以确认: " CONFIRM; echo
  [ "${GOGOGO_KEYSTORE_PASSWORD}" = "${CONFIRM}" ] || { echo "口令不一致" >&2; exit 1; }
  unset CONFIRM
fi
# Android Gradle Plugin 要求 storePassword 与 keyPassword 一致
KEY_PASSWORD="${GOGOGO_KEYSTORE_PASSWORD}"

if [ "${#GOGOGO_KEYSTORE_PASSWORD}" -lt 20 ]; then
  echo "警告：口令长度 ${#GOGOGO_KEYSTORE_PASSWORD} 偏短。建议至少 20 字符并依靠密码管理器保存。" >&2
fi

echo "==> 生成 ${KEY_BITS} 位 RSA 私钥与自签名证书"
openssl req -x509 -newkey "rsa:${KEY_BITS}" -nodes \
  -keyout "${KEY_PEM}" -out "${CERT_PEM}" \
  -days "${VALID_DAYS}" -subj "${DNAME}" \
  -addext "keyUsage=critical,digitalSignature" \
  -addext "extendedKeyUsage=codeSigning" \
  -addext "basicConstraints=critical,CA:FALSE" \
  2>/dev/null

echo "==> 打包为 PKCS#12（PBES2 / AES-256-CBC / SHA-256 MAC）"
openssl pkcs12 -export \
  -inkey "${KEY_PEM}" -in "${CERT_PEM}" \
  -name "${ALIAS}" \
  -out "${KEYSTORE}" \
  -passout "env:GOGOGO_KEYSTORE_PASSWORD" \
  -keypbe AES-256-CBC -certpbe AES-256-CBC -macalg sha256

# 立即清除中间 PEM 私钥，只保留 PKCS#12
shred -u "${KEY_PEM}" 2>/dev/null || rm -f "${KEY_PEM}"
chmod 600 "${KEYSTORE}"
chmod 644 "${CERT_PEM}"

echo
echo "==> 校验密钥库可读性"
openssl pkcs12 -in "${KEYSTORE}" -passin "env:GOGOGO_KEYSTORE_PASSWORD" -noout -info 2>/dev/null \
  || { echo "密钥库校验失败" >&2; exit 1; }

echo
echo "==> 签名证书指纹（需记录到发布说明与客户端校验逻辑）"
FPRINT=$(openssl x509 -in "${CERT_PEM}" -noout -fingerprint -sha256 | sed 's/.*=//')
SPKI=$(openssl x509 -in "${CERT_PEM}" -noout -pubkey \
  | openssl pkey -pubin -outform der 2>/dev/null \
  | openssl dgst -sha256 -binary | openssl enc -base64)
echo "    证书 SHA-256 : ${FPRINT}"
echo "    SPKI SHA-256 : ${SPKI}"

cat <<EOF

==> 完成。请立即执行以下步骤：

 1) 将密钥库存为 GitHub 仓库的 Environment Secret（production）：
      base64 -w0 '${KEYSTORE}' | gh secret set SIGNING_KEYSTORE_BASE64 --env production
    （或通过 Web UI 粘贴 base64 内容）

 2) 设置其余 Secret：
      SIGNING_KEYSTORE_PASSWORD   # 上一步输入的口令
      SIGNING_KEY_PASSWORD        # 与 storePassword 相同
      SIGNING_KEY_ALIAS           # ${ALIAS}

 3) 离线备份 '${KEYSTORE}' 与口令。密钥库丢失将导致无法发布可覆盖安装的更新。

 4) 从本地磁盘删除明文密钥库：
      shred -u '${KEYSTORE}' 2>/dev/null || rm -f '${KEYSTORE}'

 5) 记录证书指纹，用于核对已发布 APK：
      证书 SHA-256 : ${FPRINT}
      SPKI SHA-256 : ${SPKI}

 注意：'${OUT_DIR}/' 已在 .gitignore 中排除。请勿提交任何 .p12/.key/.pem 文件。
EOF
