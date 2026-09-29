#!/usr/bin/env bash
#
# 生成 vendored 二进制依赖的 SHA-256 清单
#
# 背景：app/libs/ 下的百度地图/定位 SDK（BaiduLBS_Android.jar 与 *.so）
# 是直接入库的二进制，Gradle 的 dependency locking 与
# verification-metadata 都无法覆盖它们，因此需要单独的白名单校验。
#
# 用法：./scripts/gen-vendored-checksums.sh
#
set -euo pipefail

cd "$(dirname "$0")/.."
LIBS_DIR="app/libs"
OUT="${LIBS_DIR}/VENDORED_DEPENDENCIES.sha256"

{
  echo "# vendored 二进制依赖 SHA-256 白名单"
  echo "# 由 scripts/gen-vendored-checksums.sh 生成，请勿手工编辑"
  echo "# 更新依赖时必须重新生成并在 PR 中说明来源与版本"
  echo "#"
  echo "# 已知来源：百度 LBS Android SDK（BaiduLBS_Android.jar / libBaiduMapSDK_*_v7_6_5.so）"
  echo "# 注意：该 SDK 无可用的坐标与版本清单，无法纳入 osv-scanner 等 SCA 工具，"
  echo "#       需依赖人工跟踪其安全公告。"
  cd "${LIBS_DIR}"
  find . -type f \( -name '*.jar' -o -name '*.so' \) | sed 's|^\./||' | sort |
    while read -r f; do
      printf '%s  %s\n' "$(sha256sum "$f" | cut -d' ' -f1)" "$f"
    done
} > "${OUT}"

echo "已生成 ${OUT}："
cat "${OUT}"
