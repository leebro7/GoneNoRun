<!-- 感谢您的贡献！ -->
<!-- Thanks for your contribution! -->

### 类型 / Type

- [ ] 新特性 New feature
- [ ] 问题修复 Bug fix
- [ ] 编码风格优化 Code style optimization
- [ ] 文档更新 documentation update
- [ ] 依赖更新 Dependencies update
- [ ] 性能优化 Performance optimization
- [ ] 功能增加 Enhancement function
- [ ] 其他 Other (about what?)

<!-- 请选择你的 PR 类型。 -->

### 描述 / Description

<!-- 请在下方详细描述本 PR 的实现原理。 -->

### 相关 ISSUE / Related ISSUE

### 安全影响自评 / Security Impact Checklist

<!--
  本项目曾发生过发布签名私钥随源码公开的严重事件，因此对以下几类变更
  实施强制自评。若任一项为「是」，请在描述中说明理由并获得维护者确认。
-->

- [ ] **无安全影响** —— 本 PR 不涉及以下任何类别
- [ ] 修改了 `.github/workflows/`（CI/CD 权限、触发条件、Secrets 使用）
- [ ] 修改了 `AndroidManifest.xml`（新增权限或组件）
- [ ] 修改了签名、密钥或 `app/build.gradle` 中的 signingConfig
- [ ] 新增或更新了 `app/libs/` 下的 vendored 二进制（需同步更新
      `VENDORED_DEPENDENCIES.sha256` 并说明来源与版本）
- [ ] 新增了 `res/xml/` 下的安全策略（network security config / provider paths 等）
- [ ] 修改了自更新逻辑（`MainActivity` 更新相关方法 / `UpdateVerifier`）
- [ ] 引入了新的第三方 Gradle 依赖（需说明来源与用途）
- [ ] 新增了对日志、外部存储或用户位置数据的访问

### 本地校验 / Local Verification

<!-- 提交前请确认以下命令通过；CI 也会执行同样的检查 -->

- [ ] `node scripts/check-workflow-policy.mjs` 通过
- [ ] `node scripts/check-workflow-policy.test.mjs` 通过
- [ ] `(cd app/libs && sha256sum -c VENDORED_DEPENDENCIES.sha256)` 通过
- [ ] `gitleaks detect --source . --config .gitleaks.toml` 无告警（如已安装）

### 合并检测 / Merge Check

<!-- 合并 PR 时检查以下事项（提交 PR 时无需关心） -->

- [ ] 文档是否更新 If document is updated or not
- [ ] 变更记录是否记录 If changelog is updated or not
- [ ] 若涉及权限或自更新逻辑，是否已在真机验证
