# 发布流程

本流程与历史版本的差别：**凭据集中、单次签名、人工审批、可校验产物**。

---

## 0. 一次性准备

### 0.1 生成全新签名身份

```bash
./scripts/generate-signing-key.sh
```

脚本会：
- 生成 RSA 4096 私钥与自签名证书（默认 30 年有效期）
- 打包为 PKCS#12（PBES2 / AES-256-CBC / SHA-256 MAC）
- 输出证书 SHA-256 与 SPKI SHA-256 指纹
- 提示后续步骤

> ⚠️ 绝不复用历史版本中已公开的密钥库。原因见 `docs/SECURITY-HARDENING.md` §1。

### 0.2 配置 GitHub Environment 与 Secrets

在仓库 **Settings → Environments** 新建环境 `production`，并配置：

- **Required reviewers**：至少 1 人（建议 2 人）
- **Deployment branches and tags**：限制为 `main` 与 `v*` tag

在该 Environment 下添加 Secrets：

| Secret | 内容 |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | `base64 -w0 .signing/release.p12` 的输出 |
| `SIGNING_KEYSTORE_PASSWORD` | 密钥库口令 |
| `SIGNING_KEY_PASSWORD` | 与上相同（Android Gradle Plugin 要求一致） |
| `SIGNING_KEY_ALIAS` | 密钥别名（脚本默认 `gogogo-release`） |
| `MAPS_API_KEY` | 百度地图 AK |
| `MAPS_SAFE_CODE` | 百度地图安全码 |

> 这些 Secret 必须放在 **Environment** 而非仓库级别：Environment 受审批门控，
> 未批准时不会注入到作业中，这是 F-02 的核心整改。

### 0.3 启用治理控制

- `main` 分支保护：Require pull request + Require review from CODEOWNERS
  + Require signed commits + Require status checks（`Workflow Policy Check`、`Secret Scan`、`Build Debug APK`、`Analyze (java)`）
- Settings → Actions → General：Workflow permissions 设为 **Read repository contents**
- 启用 Secret Scanning 与 Push protection

### 0.4 安装本地提交前防线

```bash
cp scripts/pre-commit-secret-guard.sh .git/hooks/pre-commit
chmod +x .git/hooks/pre-commit
```

### 0.5 生成依赖校验元数据（需要 JDK）

```bash
./gradlew --write-verification-metadata sha256 help   # 审查 diff 后提交
./gradlew dependencies --write-locks                  # 提交 gradle.lockfile
```

---

## 1. 常规发布

```bash
# 1) 更新版本号
#    app/build.gradle: versionCode / versionName
#    注意 tag 必须为 v<versionName>，客户端据此推导资产名
#    例：versionName '1.13.0' → tag v1.13.0

# 2) 本地校验
node scripts/check-workflow-policy.mjs
node scripts/check-workflow-policy.test.mjs
(cd app/libs && sha256sum -c VENDORED_DEPENDENCIES.sha256)

# 3) 提交并入 main
git commit -am "chore(release): 1.13.0"
git push origin main

# 4) 打 tag 触发发布
git tag v1.13.0
git push origin v1.13.0
```

`build-release.yml` 随即执行：

1. **校验 tag 与 `versionName` 一致**（`v<versionName>`），不一致直接失败 ——
   客户端按 tag 推导资产名，不一致会导致自更新静默失效
2. **等待人工审批**（Environment `production` 的 required reviewers）——
   审批只在此处发生一次，`publish` 作业刻意不绑定 Environment，以免要求二次审批
3. 还原签名密钥库到 runner 临时目录
4. `./gradlew assembleRelease` —— 由 Gradle **一次**完成签名
5. `apksigner verify` 断言已签名，并**比对 APK 证书指纹与密钥库证书指纹**，
   确保发布物确由预期密钥签署
6. 生成 `.sha256`、`release-notes.md`（含 `SHA-256:` 行）与已签名的 provenance
7. 上传产物 → `publish` 作业创建 **draft** Release

> `release-notes.md` 的格式与客户端 `MainActivity.parseSha256FromReleaseBody()`
> 的解析逻辑是耦合的：正文必须包含 `SHA-256: <64位十六进制>`。
> 该格式已做端到端校验（生成 → 客户端正则解析 → 命中 APK 哈希而非证书哈希）。

---

## 2. 发布后的必要人工步骤

草稿 Release 不会自动公开。发布前必须：

1. 下载草稿中的 APK，本地复核签名证书指纹：
   ```bash
   apksigner verify --print-certs Go_1.13.0_arm64-v8a_release.apk
   ```
   指纹应与 `docs/SECURITY-HARDENING.md` 记录的、以及 `generate-signing-key.sh`
   输出的证书 SHA-256 一致。
2. 复核 Release 正文中的 `SHA-256:` 行与本地计算值一致：
   ```bash
   sha256sum Go_1.13.0_arm64-v8a_release.apk
   ```
3. 确认无误后手动将草稿转为正式发布。

> 客户端自更新**强制**校验这两项中的 SHA-256 与 APK 签名证书。若 Release 正文
> 缺少 `SHA-256: <64位十六进制>` 行，客户端的更新按钮会被禁用（默认拒绝）。

---

## 3. 客户端更新校验的约定

`MainActivity.checkUpdateVersion()` 依赖以下约定，请勿改动而不改客户端：

| 约定 | 说明 |
|---|---|
| tag 格式 | `v<versionName>`，如 `v1.13.0` |
| 资产名格式 | `Go_<versionName>_arm64-v8a_release.apk` |
| 正文必须包含 | `SHA-256: <64 位十六进制>` |
| 下载地址 | 必须为 `https://` |

任一缺失 → 更新被拒绝（fail closed），这是刻意的设计。

---

## 4. 密钥轮换

Android 支持在保留应用身份的前提下轮换签名密钥。`UpdateVerifier.isSignedBySameKey()`
使用「已安装证书集合 ∩ 待安装证书集合」求交，因此已为轮换留出空间。

轮换步骤概要（详见 Android 官方文档）：

1. 生成新密钥，创建轮换证明文件（`apksigner rotate`）
2. 用旧密钥签名包含新密钥的过渡版本
3. 观察过渡版本覆盖率后再用新密钥签名
4. 更新 `UpdateVerifier` 依赖的系统签名历史（`SigningInfo.getSigningCertificateHistory()` 已处理）

> 轮换周期建议 ≤ 24 个月。历史版本的证书有效期为 50 年且从未轮换，属反面案例。
