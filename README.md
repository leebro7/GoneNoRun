<p align="center">
<img src="./docs/images/LOGO.png" height="80"/>
</p>

<div align="center">

[![license](https://img.shields.io/github/license/leebro7/GoneNoRun)](https://github.com/leebro7/GoneNoRun/blob/main/LICENSE)
[![Build APK](https://github.com/leebro7/GoneNoRun/actions/workflows/build-apk.yml/badge.svg)](https://github.com/leebro7/GoneNoRun/actions/workflows/build-apk.yml)
[![Security Gate](https://github.com/leebro7/GoneNoRun/actions/workflows/security-gate.yml/badge.svg)](https://github.com/leebro7/GoneNoRun/actions/workflows/security-gate.yml)
[![CodeQL](https://github.com/leebro7/GoneNoRun/actions/workflows/codeql-analysis.yml/badge.svg)](https://github.com/leebro7/GoneNoRun/actions/workflows/codeql-analysis.yml)

</div>

<div align="center">
影梭 - 用于 Android 8.0+ 的无需 ROOT 权限的虚拟定位 APP
<br/>
<b>本仓库是「安全加固基线」，派生自上游 <a href="https://github.com/ZCShou/GoGoGo">ZCShou/GoGoGo</a>，不是上游发行版</b>
</div>

---

## 这个仓库是什么

影梭是一个基于 Android 调试 API + 百度地图/定位 SDK 实现的安卓定位修改工具，同时带一个可自由控制移动的摇杆。

本仓库是它的**安全加固版本**：功能与上游一致，但修复了历史版本中一批高危缺陷，并把安全控制固化成可回归的门禁。功能说明、截图与使用体验请以上游为准。

### 为什么必须另起一个仓库

历史版本存在一个决定性缺陷：**Android 发布签名私钥被公开提交，且口令硬编码在构建脚本里。**

| 项 | 历史事实 |
|---|---|
| 密钥库 | `keystore/GoGoGo.jks`，2022-06-18 入库（commit `bf70ec1`）后从未移除 |
| 口令 | `app/build.gradle` 明文写死 `storePassword 'GoGoGo'` |
| `.gitignore` | `*.jks` 规则被**注释掉**，等于主动放行 |
| 旧证书 SHA-256 | `6eec4ddd865b85482bd5a97ef4ceaa14dded5c3a6cdcbffd4eccb78c54eff081` |

Android 以 APK 的签名证书作为更新信任锚。**持有该私钥的任何人都能签发一个被存量用户设备当作合法更新接受的 APK** —— 不需要破解、不需要仓库写权限、不需要任何凭据，因为仓库是公开的、口令就写在旁边。

> 第一性原理：一个已经公开过的私钥，不能通过任何配置修复重新变得可信。
> 因此本仓库**不复用**旧签名身份，改用全新密钥库；旧证书一律视为作废。

### 与上游的差异（加固摘要）

| 领域 | 控制 |
|---|---|
| 签名与密钥 | 密钥材料不入库（`*.jks/*.p12/*.pem/*.key`、`keystore/`、`.signing/`）；构建脚本无明文凭据（env → `keystore.properties`）；凭据缺失时**不签名而非失败**，使 fork PR 无需凭据也能校验；发布凭据绑定 `environment: production` 人工审批；由 Gradle **单次**完成签名 |
| CI/CD 供应链 | 全部 Action 固定 commit SHA；顶层 `permissions: contents: read`，写权限仅下沉到 `publish` 作业；禁止 `pull_request_target`；发布只由 `tags: ['v*']` 触发；PR 构建不接触任何 Secret；vendored 二进制有独立 SHA-256 清单 |
| 应用运行时 | 权限最小化（移除 11 项多余/特权声明）；默认禁止明文流量；关闭备份与设备迁移；FileProvider 收窄到 `Logs/`、`Updates/`；日志移入应用内部存储；修复失效的「关闭日志」开关 |
| 自更新链路 | `UpdateVerifier`：SHA-256 + APK 签名证书**双重校验**，任一失败即中止；资产按文件名精确匹配（不再取 `assets[0]`）；拒绝路径穿越；仅接受 HTTPS |
| 门禁 | `Security Gate` 复合 Action：工作流策略 R1–R8、全历史密钥扫描、未导入符号、XML 结构、vendored 校验和、应用不变量（含「运行时申请的权限必须已在清单声明」） |

细节、证据与红线清单见 [`docs/SECURITY-HARDENING.md`](docs/SECURITY-HARDENING.md)；审计发现逐条对照见 [`docs/AUDIT-REMEDIATION.md`](docs/AUDIT-REMEDIATION.md)。

---

## 构建

需要 **JDK 17** 与 **Android SDK**（`compileSdk 36` / `buildToolsVersion 36.0.0`）。

```bash
# 调试包（使用 Android SDK 自动生成的调试密钥，无需任何凭据）
./gradlew assembleDebug

# 发布包（需要签名凭据；缺省时该变体不签名而非报错）
export GOGOGO_KEYSTORE_FILE=/path/to/release.p12
export GOGOGO_KEYSTORE_PASSWORD='<强口令>'
export GOGOGO_KEY_PASSWORD='<与上面相同>'
export GOGOGO_KEY_ALIAS=gogogo-release
./gradlew assembleRelease
```

生成新的发布签名身份（口令不落盘、私钥不入库）：

```bash
GOGOGO_KEYSTORE_PASSWORD='<强口令>' ./scripts/generate-signing-key.sh
```

### CI

| 工作流 | 作用 |
|---|---|
| `Build APK (no secrets)` | 每次推送/PR 执行 `assembleDebug` 并上传产物，全程不接触任何 Secret |
| `Security Gate` | 策略、密钥、供应链与应用不变量的一票否决门禁 |
| `Build Check` / `CodeQL` | 静态检查与代码扫描 |
| `Build & Release` | 仅 `v*` tag 触发，需 `production` 环境审批 |

发布流程（Environment 审批、单次签名、发布前指纹核对）见 [`docs/RELEASE-PROCESS.md`](docs/RELEASE-PROCESS.md)。

---

## 安装与权限

```bash
adb install -r Go_x.y.z_arm64-v8a_release.apk
```

1. 安装后启动影梭，同意协议；
2. 授予**定位权限**（精确或大致均可；应用不会因权限被拒而把你挡在门外，只会降级地图定位）；
3. 到 **开发者选项 → 选择模拟位置信息应用** 中选中影梭 —— 这是模拟定位的真正授权入口，`ACCESS_MOCK_LOCATION` 不会出现在运行时授权弹框里；
4. 单击地图选点，点击启动。

| 权限 | 用途 |
|---|---|
| `ACCESS_FINE/COARSE_LOCATION` | 地图定位图层 |
| `ACCESS_MOCK_LOCATION` | 注册模拟位置提供者（**由开发者选项授权**） |
| `SYSTEM_ALERT_WINDOW` | 摇杆悬浮窗 |
| `FOREGROUND_SERVICE` / `POST_NOTIFICATIONS` | 持续模拟定位与常驻通知 |
| `REQUEST_INSTALL_PACKAGES` | 应用内自更新 |
| `INTERNET` / `ACCESS_WIFI_STATE` / `ACCESS_NETWORK_STATE` | 地图、逆地理编码、更新检查 |

`ACCESS_BACKGROUND_LOCATION`、`READ_LOGS`、`READ_PHONE_STATE`、`WRITE_SETTINGS`、外部存储读写等**已全部移除**。

---

## 签名身份与校验

本仓库的发布包使用**全新**签名证书（旧证书已作废）：

| 项 | 值 |
|---|---|
| DN | `CN=GoGoGo_Release, O=GoGoGo, C=CN` |
| 证书 SHA-256 | `84:9C:20:2C:7B:0B:24:C2:1C:78:A2:33:0E:45:73:99:C2:B6:A3:A7:79:E0:A2:5D:20:E7:DA:0A:CF:89:85:5E` |
| SPKI SHA-256 | `ZIXuS0qHV1ZBk/hGrnazUWKdHEC5YzbR/Gc39uO+P0w=` |
| 密钥库参数 | PKCS#12 / RSA-4096 / PBES2-AES-256-CBC / SHA-256 MAC，有效期 30 年 |

核对你手上的 APK 是否由该身份签署：

```bash
apksigner verify --print-certs your.apk | grep 'certificate SHA-256'
```

**若指纹是 `6eec4ddd…`（旧证书），请视为可疑制品并丢弃。**

---

## 已知限制与尚未验证

诚实优先：以下事项**尚未**由本仓库验证，不要当成已完成。

| # | 事项 | 说明 |
|---|---|---|
| 1 | 真机回归 | 模拟定位实际生效、摇杆悬浮窗、百度 SDK 在「默认禁止明文流量」下能否工作、自更新全链路，均需真机验证 |
| 2 | 存量用户不在保护范围 | 新签名身份与旧包名 `com.zcshou.gogogo` 的关系尚未决定（改用新包名重装，或走 Android 密钥轮换），需维护者决策 |
| 3 | 应用内更新通道 | `MainActivity` 仍查询上游 `zcshou/gogogo` 的 Release；证书校验按设计会拒绝非本签名制品，因此**在改为本仓库地址前，应用内自动更新等于关闭**（失败方向是安全的） |
| 4 | 来源证明强度 | `provenance.json` 由发布签名密钥自身签署，属同一信任根自证，**不等于** Sigstore keyless 或独立 KMS 身份 |
| 5 | 依赖元数据 | `verification-metadata.xml` / `gradle.lockfile` 尚未生成 |
| 6 | 平台侧配置 | 分支保护、Environment 审批人、CODEOWNERS 的生效状态属 GitHub 侧设置 |

---

## 参考与致谢

上游作者与项目：[ZCShou/GoGoGo](https://github.com/ZCShou/GoGoGo)。上游 README 提到的参考实现（[MockGPS](https://github.com/Hilaver/MockGPS)、[together-go](https://github.com/bxxfighting/together-go)、[Mocklation](https://github.com/P72B/Mocklation)）同样在此致谢。

### 上游作者的声明（原意保留）

上游作者明确声明：本应用**仅用于学习 Android + 百度地图的实现方式**，不赞同也不支持将其用于游戏或校园运动类应用的作弊行为；对未遵守 GPLv3 协议进行再分发、改名加广告等行为保留追究权利。

### 免责声明

本项目仅供学习与研究使用。使用者应自行承担因使用本软件产生的一切后果；请勿用于任何违反法律法规或服务条款的场景。

---

## 许可证

GPL-3.0-only © ZCShou（上游）。本仓库的加固改动同样以 **GPL-3.0-only** 发布，衍生自上游项目。
