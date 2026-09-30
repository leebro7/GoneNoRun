# 安全加固说明

本文档记录本仓库相对于历史版本所做安全加固的**原因、控制点与不可验证事项**。
所有结论均可追溯到具体文件与行号。

---

## 1. 背景：被修复的根本问题

历史版本存在一个决定性缺陷：**Android 发布签名私钥被公开提交，且口令硬编码在构建脚本中。**

| 项 | 历史事实 |
|---|---|
| 密钥库路径 | `keystore/GoGoGo.jks`（PKCS#12，2510 字节） |
| 入库时间 | 2022-06-18（commit `bf70ec1`），此后从未移除 |
| 口令 | `app/build.gradle` 中明文写死 `storePassword 'GoGoGo'`（debug 与 release 共用） |
| `.gitignore` | `*.jks` 规则被**注释掉**（`#*.jks`），等于主动放行 |
| 私钥 | RSA 2048，含私钥指数；证书 `CN=ZCShou` 自签名，有效期 2020-08-13 → **2070-08-01** |
| 证书 SHA-256 | `6eec4ddd865b85482bd5a97ef4ceaa14dded5c3a6cdcbffd4eccb78c54eff081` |

**为何这是致命的**：Android 以 APK 的签名证书作为应用更新的唯一信任锚。持有该私钥的
任何人都能签署一个被存量用户设备**接受为合法更新**的恶意 APK，且无需破解、无需仓库
写权限、无需任何凭据——仓库是公开的，口令就写在旁边。

### 第一性原理结论

> 一个已经公开过的私钥不能通过任何配置修复重新变得可信。
> 因此本仓库**不复用**该签名身份，而是要求使用全新的密钥库（见
> `scripts/generate-signing-key.sh`），旧密钥必须作废。

这也意味着：**存量用户不在可保护范围内**，除非维护者通过 Android 密钥轮换机制完成
迁移（见 §5 待人工确认事项）。

---

## 2. 加固控制清单

### 2.1 签名与密钥（对应原则 6/7）

| 控制 | 实现位置 | 说明 |
|---|---|---|
| 密钥材料不入库 | `.gitignore`（`*.jks/*.p12/*.pem/*.key`、`keystore/`、`.signing/`） | 历史缺陷的直接原因就是这些规则被注释 |
| 构建脚本无明文凭据 | `app/build.gradle` 的 `signingValue()` | 依次读取环境变量 → `keystore.properties`（已 gitignore） |
| 凭据缺失时不签名而非失败 | `app/build.gradle` 条件创建 `signingConfigs.release` | 使 fork PR 构建无需任何凭据即可完成校验 |
| 发布凭据绑定人工审批 | `.github/workflows/build-release.yml` 的 `environment: production` | 未获批准时签名 Secret 不会被注入 |
| 单次签名 | `build-release.yml` 由 Gradle 一次完成签名 | 去除历史版本「Gradle 签一次 + 第三方 Action 再签一次」的反模式 |
| 密钥生成脚本 | `scripts/generate-signing-key.sh` | PKCS#12 + PBES2/AES-256-CBC + SHA-256 MAC，口令不落盘 |
| 提交前拦截 | `scripts/pre-commit-secret-guard.sh` | 文件名黑名单 + 硬编码口令检测 + gitleaks 增量扫描 |
| 全历史扫描 | `build-check.yml` 的 `secret-scan` 作业 | 直接下载官方 gitleaks 二进制并校验 SHA-256，不引入第三方 Action |

### 2.2 CI/CD 与供应链（对应原则 2/3/10）

| 控制 | 实现位置 | 说明 |
|---|---|---|
| 所有 Action 固定 commit SHA | 全部 workflow | 用 GitHub API 逐个核验取得，未使用任何推测值 |
| 顶层权限只读 | 全部 workflow 的 `permissions: contents: read` | 写权限仅下沉到 `publish` 作业 |
| 禁止 `pull_request_target` | 策略校验器 R3 | 该触发方式可被 fork PR 用于获取 Secrets |
| 禁止通配 tag 触发发布 | `build-release.yml` 的 `tags: ['v*']` + R5 | 历史版本为 `tags: ['*']` |
| PR 构建不接触任何 Secret | `build-check.yml` 不再写入 `local.properties` | 历史版本在 `pull_request` 触发路径上注入 `MAPS_API_KEY` |
| 依赖完整性 | `app/build.gradle` 的 `verifyVendoredDependencies` + `app/libs/VENDORED_DEPENDENCIES.sha256` | Gradle 的 locking/verification 覆盖不到 `files()`/`fileTree()` 引入的本地二进制 |
| 依赖校验元数据与锁定 | `build.gradle` 的 `dependencyVerification` / `dependencyLocking` | 元数据需维护者本地生成后提交（本环境无 JDK） |
| Gradle 发行版哈希 | `gradle/wrapper/gradle-wrapper.properties` 的 `distributionSha256Sum` | 取自 services.gradle.org 官方校验文件 |
| 策略即代码 | `scripts/check-workflow-policy.mjs` + 对抗性测试 | R1–R7 不变量，9 个负向用例全部通过 |
| 加固不变量门禁 | `.github/actions/security-gate/action.yml` + `.github/workflows/security-gate.yml` | 复合 Action，无第三方依赖。在每次 push/PR 上强制执行：工作流策略、校验器自测、vendored 校验和、密钥扫描、应用侧安全不变量、Gradle wrapper 哈希。**已用 6 类人为回归验证其确实会阻断**（见 §2.6） |
| 发布来源证明 | `scripts/gen-provenance.mjs` + `build-release.yml` 的签署步骤 | 生成 in-toto Statement / SLSA provenance v1 形式本体，覆盖源码提交、构建参数、制品 SHA-256、builder 与 invocation id，用发布密钥签名后随 Release 发布。**局限**：与 APK 同一信任根，属自证，不等同于 Sigstore keyless 或独立 KMS 证明。验证方法见 §2.5 |

### 2.5 验证发布来源证明

```bash
# 1) 取回 provenance、其签名与发布证书
curl -LO <release>/provenance.json
curl -LO <release>/provenance.json.sig

# 2) 核验证明本体未被篡改
openssl x509 -in release.cert.pem -pubkey -noout > pub.pem
openssl dgst -sha256 -verify pub.pem -signature provenance.json.sig provenance.json
#   期望输出：Verified OK

# 3) 核验证明中登记的 APK 哈希与实际下载的 APK 一致
DECLARED=$(node -e 'console.log(require("./provenance.json").subject[0].digest.sha256)')
ACTUAL=$(sha256sum Go_<ver>_arm64-v8a_release.apk | cut -d' ' -f1)
[ "$DECLARED" = "$ACTUAL" ] && echo MATCH || echo "MISMATCH — 制品与证明不符，拒绝使用"

# 4) 核验源码提交与构建参数
node -e 'const p=require("./provenance.json").predicate;
  console.log("source :", p.buildDefinition.resolvedDependencies[0].uri);
  console.log("params :", JSON.stringify(p.buildDefinition.externalParameters));
  console.log("builder:", p.runDetails.builder.id);'
```

已实测的负向验证：改动 APK 内容后，第 3 步哈希比对必然失配 —— 证明确实绑定了制品。

### 2.6 Security Gate 的回归验证

`.github/actions/security-gate/action.yml` 的价值取决于它能否真的拦住回退。
以下 6 类人为回归已逐一验证会被阻断（在 `git archive HEAD` 的隔离副本上执行，
不污染工作树）：

| 回归 | 期望 | 实测 |
|---|---|---|
| 重新声明 `READ_LOGS` | 阻断 | ✅ `权限 READ_LOGS 重新出现在 uses-permission 声明中` |
| 把 `cleartextTrafficPermitted` 改回 `true` | 阻断 | ✅ `不变量被破坏: 明文流量默认禁止` |
| 把 FileProvider 改回 `path="."` | 阻断 | ✅ `provider_paths 中出现 path="."（范围过宽）` |
| 删除安装前的 SHA-256 校验 | 阻断 | ✅ `不变量被破坏: 更新前校验 SHA-256` |
| 在 `build.gradle` 硬编码签名口令 | 阻断 | ✅ `build.gradle 中出现字面量签名口令` |
| 把日志写回 `getExternalFilesDir` | 阻断 | ✅ `应用代码仍调用 getExternalFilesDir` |

#### 实现中记录的一个陷阱

不变量断言需要先剥离 XML/Java 注释（文件内保留了解释性注释，说明历史上移除了
什么），否则关键词会被注释误命中而产生**假失败**。但剥离后的内容**不能**经 shell
变量再传给 `grep -q`：

```bash
# 错误：实测会静默返回「不匹配」，而 grep -c 却报 1 —— 导致静默漏检
SRC=$(strip_java); printf '%s' "$SRC" | grep -q 'getExternalFilesDir'

# 正确：进程替换 + -a 强制按文本处理
grep -a -q 'getExternalFilesDir' <(strip_java)
```

该陷阱在开发过程中真实导致过一次漏检（上表第 6 行的回归最初未被发现），
修复后 6/6 全部阻断。这也是为什么门禁本身必须有负向测试。

### 2.3 应用运行时（对应原则 1/3/4/8）

| 控制 | 实现位置 |
|---|---|
| 权限最小化 | `AndroidManifest.xml`：移除 11 项多余/特权权限声明（见 §2.4） |
| 明文流量默认禁止 | `res/xml/network_security_config.xml`：`base-config cleartextTrafficPermitted="false"` |
| 禁止备份与设备迁移 | `allowBackup="false"` + `res/xml/data_extraction_rules.xml` |
| FileProvider 范围收窄 | `res/xml/provider_paths.xml`：删除 `path="."` 与 `<external-path>`，仅保留 `Logs/` 与 `Updates/` |
| 日志脱离外部存储 | `GoApplication.initXlog()` → `getFilesDir()/Logs`，级别由 `LogLevel.ALL` 改为 `DEBUG` |
| 修复失效的日志开关 | `initXlog()` 现在真正读取 `setting_log_off`（历史版本只更新 UI，无任何代码读取） |
| OTA 完整性校验 | `UpdateVerifier.java` + `MainActivity.verifyAndInstallNewVersion()`：SHA-256 + APK 签名证书双重校验，任一失败即中止 |
| 资产选择抗篡改 | `MainActivity.checkUpdateVersion()`：按文件名精确匹配（不再取 `assets[0]`），仅接受 HTTPS |
| 路径穿越防护 | `isSafeAssetFileName()`：拒绝 `../`、路径分隔符与非法字符 |
| 前台服务类型 | `ServiceGo` 声明 `foregroundServiceType="location"` |
| 广播不导出 | `MainActivity.initUpdateVersion()` 使用 `RECEIVER_NOT_EXPORTED`（API 33+） |

### 2.4 权限处置明细

| 权限 | 处置 | 理由 |
|---|---|---|
| `ACCESS_BACKGROUND_LOCATION` | 移除 | 定位由前景服务持有，不需要后台范围 |
| `READ_PHONE_STATE` | 移除 | 代码中从未使用（历史版本仅申请） |
| `READ_EXTERNAL_STORAGE` | 移除 | 日志迁入内部存储后不再需要 |
| `DOWNLOAD_WITHOUT_NOTIFICATION` | 移除 | 非必要；更新下载应可见 |
| `REPLACE_EXISTING_PACKAGE` | 移除 | **并非有效的 Android 权限常量**，且从未使用 |
| `MOUNT_UNMOUNT_FILESYSTEMS` | 移除 | `signature\|privileged`，普通应用不可获得 |
| `READ_LOGS` | 移除 | 同上 |
| `WRITE_SETTINGS` | 移除 | 特权设置写入，功能上不需要 |
| `ACCESS_MOCK_LOCATION` | 移除（**需真机验证**） | `signature\|privileged` 权限；实际授权路径是「开发人员选项 → 选择模拟位置信息应用」。见 `AndroidManifest.xml` 末尾的验证要求 |
| `CHANGE_WIFI_STATE` | 移除 | 应用只提示用户关闭 Wi-Fi，不自行修改 |
| `WAKE_LOCK` | 移除 | 代码中无 `PowerManager` 使用 |
| `ACCESS_FINE/COARSE_LOCATION` | 保留 | 核心功能 |
| `FOREGROUND_SERVICE` / `POST_NOTIFICATIONS` | 保留 | 常驻前台服务 |
| `SYSTEM_ALERT_WINDOW` | 保留 | 摇杆悬浮窗 |
| `REQUEST_INSTALL_PACKAGES` | 保留 | 应用内自更新 |
| `VIBRATE` / `INTERNET` / `ACCESS_WIFI_STATE` / `ACCESS_NETWORK_STATE` | 保留 | 常规权限 |

---

## 3. 策略校验器规则说明

`scripts/check-workflow-policy.mjs` 在 CI 中强制以下不变量（违反即构建失败）：

| 规则 | 内容 |
|---|---|
| R1 | 所有 `uses:` 必须固定到 40 位 commit SHA |
| R2 | 每个 workflow 必须有顶层 `permissions:` |
| R3 | 禁止 `pull_request_target` |
| R4 | 引用签名凭据的**作业**必须绑定 `environment:` |
| R5 | 禁止通配 tag 触发发布 |
| R6 | 顶层 `permissions` 不得授予 `contents: write` |
| R7 | 仓库中不得存在密钥库类文件 |

实现刻意不依赖第三方 YAML 解析器，以免校验器自身成为供应链风险。
`scripts/check-workflow-policy.test.mjs` 提供 9 个对抗性用例（含 6 个负向用例），
用于证明校验器确实能拦住违规——未经负向测试的校验器无法证明其有效性。

---

## 4. 本环境未能验证的事项（务必在合入前完成）

本仓库在**无 JDK、无 Android SDK** 的环境下完成，因此以下事项**未经验证**，
不能视为已完成：

| # | 事项 | 原因 | 验证方式 |
|---|---|---|---|
| V-1 | 能否成功编译 | 无 JDK / Android SDK | `./gradlew assembleRelease` |
| V-2 | `ACCESS_MOCK_LOCATION` 移除后模拟定位是否仍可授权 | 需真机 | Android 8/10/13 上检查「选择模拟位置信息应用」列表 |
| V-3 | 移除 `CHANGE_WIFI_STATE` / `WAKE_LOCK` 是否影响功能 | 需真机 | 回归测试摇杆与持续定位 |
| V-4 | 百度 SDK 是否能在 `cleartextTrafficPermitted="false"` 全局策略下工作 | 需运行时抓包 | 若 SDK 失败，按错误日志收窄 `network_security_config.xml` 的例外域名 |
| V-5 | Gradle `verification-metadata.xml` 未提交 | 需 JDK 生成 | `./gradlew --write-verification-metadata sha256 help` 后审查 diff 并提交 |
| V-6 | `gradle.lockfile` 未生成 | 同上 | `./gradlew dependencies --write-locks` |
| V-7 | ProGuard 规则是否引入 R8 警告 | 需构建 | 关注 `assembleRelease` 输出 |
| V-8 | 自更新全链路（下载 → 校验 → 安装） | 需真机 + 真实 Release | 端到端测试，并验证校验失败时确实中止 |
| V-9 | 分支保护、Environment 审批人、CODEOWNERS 生效 | 属 GitHub 平台侧配置 | `gh api repos/<owner>/<repo>/branches/main/protection` |

---

## 5. 需要人工确认的事项

1. **密钥轮换方式（最高优先）**：新的签名身份与旧包名 `com.zcshou.gogogo` 的关系如何？
   - 方案 A：改用新包名发布，明确告知用户重新安装（旧用户不再受保护）；
   - 方案 B：走 Android 密钥轮换（`apksigner rotate` / Play App Signing 密钥升级），
     使既有用户可平滑升级。
   方案选择直接影响存量用户的安全，必须由维护者决定。
2. **是否公开披露**：该私钥自 2022 年起公开可获取。建议发布安全公告，说明
   旧证书 SHA-256 指纹并指导用户核验所装 APK。是否披露及措辞由维护者决定。
3. **`secrets.SIGNING_KEY` 与旧密钥库的关系**：若 CI 中的密钥与仓库内泄露的为同一把，
   则历史 Release 制品均需视为可疑，应逐一用
   `apksigner verify --print-certs` 核对证书指纹。
4. **合规复核**：应用采集精确位置并在设备保留轨迹，属《个人信息保护法》下的敏感
   个人信息。`strings.xml` 中「不会收集任何用户数据」的表述与实际行为存在不一致，
   建议法务复核并修订文案。

---

## 6. 红线（不得回退的控制）

以下任一项被回退，都会重新打开已修复的高危问题：

- ❌ 取消 `.gitignore` 中 `*.jks` / `*.p12` / `*.pem` 的排除
- ❌ 在 `build.gradle` / `gradle.properties` / `keystore.properties` 中写入口令
- ❌ 移除 `build-release.yml` 的 `environment: production`
- ❌ 把 Action 从 SHA 改回可变 tag
- ❌ 移除 `UpdateVerifier` 的 SHA-256 或签名证书校验
- ❌ 把日志目录改回 `getExternalFilesDir()`
- ❌ 在 `provider_paths.xml` 中重新加入 `path="."` 或 `<external-path>`
- ❌ 在 `pull_request` 触发路径上注入任何 Secret
