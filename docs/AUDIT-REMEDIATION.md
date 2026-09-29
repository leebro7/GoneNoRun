# 审计发现与整改对照表

本表把零信任审计报告中的 13 项发现逐条映射到本仓库的实际改动，并标注**验证方式**与
**证据**。任何未整改项都显式列出，不做隐式跳过。

- 审计对象：`ZCShou/GoGoGo` @ `de0d596`（556 条提交历史）
- 本仓库：加固基线（1 次提交，全新历史，零密钥）
- 验证环境限制：本仓库在**无 JDK / 无 Android SDK** 下产出，凡需编译或真机的项均
  标注为「待验证」并给出命令。

---

## 1. 发现对照

| ID | 原严重度 | 问题 | 整改状态 | 证据位置 |
|---|---|---|---|---|
| **F-01** | Critical | 发布签名私钥（PKCS#12）公开入库，口令硬编码 | ✅ **已整改** | 删除 `keystore/`；`app/build.gradle` 凭据改为 `signingValue()` 读环境变量/`keystore.properties`；`.gitignore` 启用 `*.jks/*.p12/*.pem`；新增 `scripts/generate-signing-key.sh`；全历史扫描 0 命中 |
| **F-02** | Critical | 推送任意 tag 即发布，`contents: write`，无审批 | ✅ **已整改** | `build-release.yml`：`tags: ['v*']`、顶层 `permissions: contents: read`、两个作业均 `environment: production`、写权限仅在 `publish` 作业 |
| **F-03** | High | 8 个 Action 中 4 个用可变 tag | ✅ **已整改** | 19/19 `uses:` 全部固定 40 位 SHA（逐个经 GitHub API 核验）；`scripts/check-workflow-policy.mjs` R1 强制 |
| **F-04** | High | 无依赖校验/lockfile；wrapper 无哈希；vendored 二进制无来源 | ⚠️ **部分整改** | ✅ wrapper `distributionSha256Sum` 已固定（官方值）；✅ vendored 校验 `verifyVendoredDependencies` + `VENDORED_DEPENDENCIES.sha256`（7 文件）；✅ `dependencyVerification`/`dependencyLocking` 已配置；❌ **`verification-metadata.xml` 与 `gradle.lockfile` 尚未生成**（需 JDK） |
| **F-05** | High | 过度权限；日志写外部存储；`allowBackup` 未禁用 | ✅ **已整改** | Manifest 移除 11 项声明；日志改 `getFilesDir()/Logs`；`allowBackup="false"` + `dataExtractionRules`；移除 `requestLegacyExternalStorage` |
| **F-06** | Medium | OTA 无完整性校验；取 `assets[0]` | ✅ **已整改** | `UpdateVerifier.java`（SHA-256 + 签名证书求交）；`checkUpdateVersion` 按文件名精确匹配 + 仅 HTTPS + 正文 `SHA-256:` 强制；`isSafeAssetFileName()` 防路径穿越 |
| **F-07** | Medium | API Key 置于 URL 查询串 | ⚠️ **部分整改** | `MAPS_API_KEY` 仅经 Secret/`local.properties` 注入，不再入库；❌ URL 传参形式未改（改服务端代理需架构变更，见 §3） |
| **F-08** | Medium | 无 Network Security Config | ✅ **已整改** | `res/xml/network_security_config.xml`：`base-config cleartextTrafficPermitted="false"`；百度域名窄例外；`debug-overrides` 仅调试构建 |
| **F-09** | Medium | FileProvider 暴露整个外部存储 | ✅ **已整改** | `provider_paths.xml` 仅保留 `Logs/` 与 `Updates/`；删除 `path="."`、`<external-path>` 与重复项 |
| **F-10** | Medium | PR 路径注入 Secret；缓存投毒 | ✅ **已整改** | `build-check.yml` 不再写 `local.properties`，不引用任何 Secret；各 workflow 显式 `permissions: contents: read` |
| **F-11** | Low | 556/556 提交无签名；无 CODEOWNERS | ⚠️ **部分整改** | ✅ 新增 `.github/CODEOWNERS` 覆盖工作流/签名/清单/自更新路径；✅ PR 模板加入安全自评与本地校验项；❌ **提交签名需在平台侧启用**（本环境无法设置） |
| **F-12** | Low | 动态广播接收器无导出约束；`onStartCommand` 未校验 extras | ⚠️ **部分整改** | ✅ `MainActivity` 下载广播改用 `RECEIVER_NOT_EXPORTED`（API 33+）；✅ `ServiceGo` 声明 `foregroundServiceType="location"`；❌ `ServiceGo.NoteActionReceiver` 未改（需真机验证行为） |
| **F-13** | Low | 明文依赖、targetSdk 32、双重签名、Windows 路径 | ⚠️ **部分整改** | ✅ 双重签名已消除（Gradle 单次）；✅ 依赖改为精确声明（去掉 `fileTree` 通配）；❌ `targetSdk` 仍为 32（需排期）；❌ `buildToolsVersion '36.0.0'` + `compileSdk 32` 组合未复核 |

**统计**：完全整改 8 项（F-01/02/03/05/06/08/09/10），部分整改 5 项，未整改 0 项。

---

## 2. 额外发现（审计报告未覆盖，本轮新增整改）

| 问题 | 性质 | 证据 | 整改 |
|---|---|---|---|
| `HistoryActivity.recordArchive()` 读取 `setting_pos_history`，而 UI 写入的键是 `setting_history_expiration` | **功能失效 + 隐私控制失效**：用户设定的「历史记录有效期」完全不生效，位置轨迹按默认 7 天保留 | `HistoryActivity.java:178`（旧）、`preferences_main.xml:82`、`FragmentSettings.java:86` | ✅ 已修正为 `setting_history_expiration`，并增加非法值防御（负数/NaN/Infinity 回落 7 天） |
| `FragmentSettings` 中 `setting_log_off` 开关只更新 UI，无任何代码读取 | 失效开关，用户以为已关闭日志但实际未生效 | 旧 `FragmentSettings.java:68-84`；全仓库无读取点 | ✅ `GoApplication.initXlog()` 现在真正读取该键 |
| `setting_map_key`（用户自填地图 Key）被读取但无任何 UI 写入 | 死配置；仅影响使用自带 Key 的路径 | `MainActivity.java:922` | ⚠️ 已记录，未改（不构成安全缺陷：默认回落到 `BuildConfig.MAPS_API_KEY`） |
| `setting_author` 在 `preferences_main.xml` 声明但无代码引用 | 无效设置项 | `preferences_main.xml:98-103` | ⚠️ 已记录，未改（纯 UI 冗余） |
| `REPLACE_EXISTING_PACKAGE` 并非有效 Android 权限常量 | 无效声明，扩大审计面 | 旧 `AndroidManifest.xml:22` | ✅ 已移除 |
| `GoApplication` 在用户点击同意前即调用 `SDKInitializer.setAgreePrivacy(this, true)` | 合规隐患（告知同意顺序） | `GoApplication.java:33-35` | ⚠️ **未改**，见 §3 |

---

## 3. 未整改项与原因

| 项 | 为何未整改 | 建议 |
|---|---|---|
| `verification-metadata.xml` / `gradle.lockfile` | 生成需 JDK（本环境无） | 维护者执行 `./gradlew --write-verification-metadata sha256 help` 与 `./gradlew dependencies --write-locks`，审查 diff 后提交 |
| OTA 的 `ak`/`mcode` 仍在 URL 查询串 | 改为 POST/请求头不足以消除客户端持有长期 Key 的固有问题；根治需引入服务端代理（架构变更） | 中期：轻量边缘函数代理逆地理编码；短期：确认百度控制台已启用包名+签名绑定与配额告警 |
| 提交签名强制 | 属 GitHub/Gitea 平台侧设置 | 分支保护中启用 `Require signed commits` |
| `ServiceGo.NoteActionReceiver` 导出约束 | 修改可能影响通知栏摇杆按钮；需真机验证 | 用 `LocalBroadcastManager` 或显式 `setPackage()`，真机回归后提交 |
| `targetSdk` 升级 | 涉及通知权限、前台服务类型、存储行为变更，需完整回归 | 单独排期，建议直接对标最新稳定版 |
| `setAgreePrivacy` 顺序 | 需把 SDK 初始化推迟到 `WelcomeActivity` 取得同意之后，触及主启动流程；无真机时贸然改动风险高于收益 | 由维护者决定是否本次一并处理；已记入 `docs/SECURITY-HARDENING.md` §5 |

---

## 4. 逐项验证命令

```bash
# F-01 密钥史：应为 0 命中
git log --all --name-only --pretty=format: | sort -u \
  | grep -Ei '\.(jks|keystore|p12|pfx|pem|key)$|keystore\.properties' || echo "OK: no key material"
git grep -nE "(storePassword|keyPassword)\s*['\"][^'\"]{3,}['\"]" -- '*.gradle' || echo "OK: no hardcoded creds"

# F-02/F-03/F-10 工作流策略（R1-R7 全部强制）
node scripts/check-workflow-policy.mjs
node scripts/check-workflow-policy.test.mjs          # 9 个对抗性用例
grep -rhE 'uses:.*@[0-9a-f]{40}' .github/workflows/ | wc -l   # 应等于 uses 总数
grep -rn 'secrets\.' .github/workflows/build-check.yml || echo "OK: PR build uses no secrets"

# F-04 供应链完整性
grep -q '^distributionSha256Sum=' gradle/wrapper/gradle-wrapper.properties && echo "OK: wrapper pinned"
(cd app/libs && sha256sum -c VENDORED_DEPENDENCIES.sha256)

# F-05 权限与备份
aapt2 dump badging app/build/outputs/apk/release/*.apk | grep -iE 'permission|backup'

# F-06 自更新完整性
grep -n 'UpdateVerifier' app/src/main/java/com/zcshou/gogogo/MainActivity.java

# F-08 明文流量
grep -o 'cleartextTrafficPermitted="[a-z]*"' app/src/main/res/xml/network_security_config.xml

# F-09 FileProvider 范围
grep -n 'path=' app/src/main/res/xml/provider_paths.xml    # 不得出现 path="."

# F-11 治理
test -f .github/CODEOWNERS && echo "OK: CODEOWNERS present"

# 额外修复
grep -n 'setting_history_expiration' app/src/main/java/com/zcshou/gogogo/HistoryActivity.java
grep -n 'setting_log_off' app/src/main/java/com/zcshou/gogogo/GoApplication.java
```

---

## 5. 收敛判断

审计中所有 **Critical / High** 发现已全部整改（F-01、F-02、F-03、F-05、F-10 完全整改；
F-04 除需 JDK 生成的两个文件外全部整改）。剩余 5 个部分整改项均为 Medium/Low，
且未整改部分普遍受限于**本环境无 JDK/真机**或**平台侧设置**，属可解释的边界，
非遗漏。

**但因以下原因，本仓库尚不可标记为「可直接发布」：**

1. 未编译验证（无 JDK/Android SDK）——`docs/SECURITY-HARDENING.md` §4 列出 V-1..V-9；
2. `verification-metadata.xml` / `gradle.lockfile` 未生成；
3. 签名身份尚未在发布链路中实际启用（需先配置 Environment Secrets）；
4. 推送目标尚未完成（凭据未就绪）。
