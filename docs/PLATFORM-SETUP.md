# 平台侧配置清单（发布之前必须完成）

本文件列出**代码之外**、必须由仓库管理员在平台侧完成的配置。
这些事项无法通过提交代码完成，也是当前发布链路的唯一阻塞点。

当前状态（截至最近一次实测）：

| 项 | 状态 | 证据 |
|---|---|---|
| `production` environment | ✅ 已创建 | GitHub API `environments total_count: 1` |
| `production` 保护规则 | ⚠️ **0 条** | 即发布**不会等待人工审批** |
| 6 个签名/地图 Secret | ❌ **缺失** | `Build Release` run #2 在第 6 步 `Materialize signing keystore` 失败 |
| `v1.12.3` tag | ✅ 已推送 | 但构建因缺少 Secret 而中止 |

失败步骤的原文是刻意的 fail-closed 设计：

```
::error::未配置 SIGNING_KEYSTORE_BASE64，拒绝以未签名方式发布
```

---

## 步骤 1：生成全新的签名身份（在你自己机器上执行）

```bash
cd gogogo-secure
./scripts/generate-signing-key.sh
```

> ⚠️ **不要复用任何历史密钥。** 旧仓库的发布私钥已公开（见
> `docs/SECURITY-HARDENING.md` §1）。

脚本会：
- 生成 RSA 4096 私钥与自签名证书（默认 30 年）
- 打包为 PKCS#12（PBES2 / AES-256-CBC / SHA-256 MAC）
- 删除中间 PEM 私钥，只保留 `.signing/release.p12`（已 gitignore）
- 输出**证书 SHA-256 指纹**与 SPKI —— 请记录下来

建议口令 ≥ 20 字符，用密码管理器保存。

## 步骤 2：创建 `production` environment

**Settings → Environments → New environment**，名称必须**正好**是 `production`
（workflow 里写死了这个名字）。

配置：

- ✅ **Required reviewers**：填你自己（建议再加一人）
  > 当前保护规则为 0 条，意味着现在任何人推 tag 都会直接构建并发布，
  > 没有第二人把关。这是 F-02 整改的核心闸门，请务必开启。
- ✅ **Deployment branches and tags**：限定 `main` 与 `v*`
  > 防止从任意分支/tag 发布

## 步骤 3：在 `production` 下添加 6 个 Secret

**Settings → Environments → production → Environment secrets → Add secret**

> ⚠️ **必须放在 Environment 下，不能放仓库级。** 放仓库级会让 Secret 对
> 所有 workflow 可见，且**绕过审批闸门** —— 那样整个 F-02 整改就失效了。

| Secret 名称 | 值 |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | `base64 -w0 .signing/release.p12` 的输出 |
| `SIGNING_KEYSTORE_PASSWORD` | 步骤 1 输入的口令 |
| `SIGNING_KEY_PASSWORD` | 与上相同（Android Gradle Plugin 要求一致） |
| `SIGNING_KEY_ALIAS` | 脚本输出的别名（默认 `gogogo-release`） |
| `MAPS_API_KEY` | 百度地图 AK |
| `MAPS_SAFE_CODE` | 百度地图安全码 |

用 `gh` 一次性配置（可选）：

```bash
gh secret set SIGNING_KEYSTORE_BASE64 --env production --repo leebro7/GoneNoRun \
  < <(base64 -w0 .signing/release.p12)
gh secret set SIGNING_KEYSTORE_PASSWORD --env production --repo leebro7/GoneNoRun
gh secret set SIGNING_KEY_PASSWORD      --env production --repo leebro7/GoneNoRun
gh secret set SIGNING_KEY_ALIAS         --env production --repo leebro7/GoneNoRun
gh secret set MAPS_API_KEY              --env production --repo leebro7/GoneNoRun
gh secret set MAPS_SAFE_CODE            --env production --repo leebro7/GoneNoRun
```

## 步骤 4：确认 Actions 权限

**Settings → Actions → General → Workflow permissions**

设为 **Read repository contents and packages permissions**。

这不会阻止发布：`build-release.yml` 的 `publish` 作业已显式声明
`contents: write`，作业级声明不受仓库默认值限制。

建议同时开启：
- **Allow GitHub Actions to create and approve pull requests**：关闭
- **Secret scanning** 与 **Push protection**：开启（公开仓库免费）

## 步骤 5：自检

```bash
./scripts/preflight-release.sh            # 本地检查
gh auth login                             # 若尚未登录
./scripts/preflight-release.sh --remote   # 含 environment / secret 检查
```

## 步骤 6：手动触发一次验证（推荐）

在正式打 tag 之前，先用 `workflow_dispatch` 验证能否编译出 APK ——
本项目**从未成功编译过**，构建层可能还有未暴露的问题。

**Actions → Build Release → Run workflow → 分支选 `main` → tag 留空 → Run**

`tag` 留空时会自动解析为 `v<versionName>`（当前 = `v1.12.3`），
因此版本校验会通过。注意：`workflow_dispatch` 时 `GITHUB_REF_NAME` 是
**分支名**而不是 tag 名，workflow 已对此做了区分处理。

## 步骤 7：正式发布

```bash
# 改动版本号（app/build.gradle 的 versionCode / versionName），例如 1.13.0
git commit -am "chore(release): 1.13.0"
git push github main

git tag -a v1.13.0 -m "GoGoGo 1.13.0"
git push github v1.13.0
```

> **tag 必须正好是 `v<versionName>`。** 客户端按 tag 推导资产名
> （`Go_<version>_arm64-v8a_release.apk`），不一致会导致自更新静默失效 ——
> workflow 会直接阻断并报错。
>
> **必须推到 `github`**，推 `gitea` 不会触发 GitHub Actions（两套独立系统）。

---

## 排查：构建失败时如何定位

不要靠猜。用 API 取失败的具体步骤：

```bash
# 最近一次运行的各作业与步骤状态
RUN=$(gh api repos/leebro7/GoneNoRun/actions/runs --jq '.workflow_runs[0].id')
gh api repos/leebro7/GoneNoRun/actions/runs/$RUN/jobs \
  --jq '.jobs[] | "JOB: \(.name) [\(.conclusion)]", (.steps[] | "  \(.number) \(.name) -> \(.conclusion)")'
```

下载日志需要 admin 权限（`403 Must have admin rights`），但**步骤级状态
不需要** —— 上面的命令足以定位到具体哪一步失败。

### 已知失败模式

| 失败步骤 | 原因 | 处理 |
|---|---|---|
| `Assert tag matches versionName` | tag 与 `versionName` 不一致 | 打 `v<versionName>` 形式的 tag |
| `Materialize signing keystore` | `SIGNING_KEYSTORE_BASE64` 未配置或不在 Environment 下 | 见步骤 3 |
| `Record signing certificate fingerprint` | 口令/别名与密钥库不匹配 | 重新核对 3 个 Secret |
| `Build release (signed once by Gradle)` | 编译错误（ProGuard/依赖/compileSdk） | 看日志；本项目尚未经过编译验证 |
| `Assert APK is signed by the release key` | APK 证书与密钥库证书指纹不一致 | 说明 Gradle 未用预期密钥签名，检查 Secret |
| `Publish GitHub Release` | 步骤级 `contents: write` 被覆盖 | 确认未把仓库默认权限设为更严且覆盖作业级 |
