#!/usr/bin/env node
/**
 * 生成构建来源证明（in-toto Statement / SLSA provenance v1 形式）
 *
 * 设计说明：
 *   * 独立成文件而非内联在 workflow 中 —— YAML run 块的内容带有缩进，使用
 *     heredoc 时结束标记必须顶格（<<-EOF 只剥离制表符、不剥离空格），极易出
 *     错；脚本文件没有这个陷阱。
 *   * 仅使用 Node 内置能力，无第三方依赖。Node 在 CI 中由 setup-node 保证。
 *
 * 输入（环境变量）：
 *   APK_NAME, APK_SHA, RELEASE_TAG（发布 tag，如 v1.12.3）,
 *   GITHUB_SERVER_URL, GITHUB_REPOSITORY, GITHUB_SHA,
 *   GITHUB_RUN_ID, GITHUB_RUN_ATTEMPT, STARTED_ON
 *
 * 注意：不要用 GITHUB_REF_NAME 记录版本 —— 手动触发时它是分支名（如 main），
 * 只有 tag 推送时才是 tag 名。发布版本应取 RELEASE_TAG。
 *
 * 输出：stdout 打印 JSON
 *
 * 局限：本证明由发布签名密钥签署（见 build-release.yml），属「同一信任根的
 * 自证」，强度低于独立的 Sigstore keyless 签名或云 KMS 身份证明。
 */

const e = process.env;

const required = ['APK_NAME', 'APK_SHA', 'GITHUB_REPOSITORY', 'GITHUB_SHA'];
const missing = required.filter((k) => !e[k]);
if (missing.length) {
  process.stderr.write(`缺少必需的环境变量: ${missing.join(', ')}\n`);
  process.exit(1);
}

if (!/^[0-9a-f]{64}$/.test(e.APK_SHA)) {
  process.stderr.write(`APK_SHA 不是合法的 64 位十六进制: ${e.APK_SHA}\n`);
  process.exit(1);
}

const doc = {
  _type: 'https://in-toto.io/Statement/v1',
  subject: [{ name: e.APK_NAME, digest: { sha256: e.APK_SHA } }],
  predicateType: 'https://slsa.dev/provenance/v1',
  predicate: {
    buildDefinition: {
      buildType: 'https://github.com/ZCShou/GoGoGo/buildtypes/gradle-android@v1',
      externalParameters: {
          releaseTag: e.RELEASE_TAG || '',
          versionName: (e.RELEASE_TAG || '').replace(/^v/, ''),
        abi: 'arm64-v8a',
        gradleDistribution: '8.13',
      },
      resolvedDependencies: [
        { uri: `git+${e.GITHUB_SERVER_URL}/${e.GITHUB_REPOSITORY}@${e.GITHUB_SHA}` },
      ],
    },
    runDetails: {
      builder: {
        id: `${e.GITHUB_SERVER_URL}/${e.GITHUB_REPOSITORY}/actions/runs/${e.GITHUB_RUN_ID}`,
      },
      metadata: {
        invocationId: `${e.GITHUB_RUN_ID}-${e.GITHUB_RUN_ATTEMPT}`,
        startedOn: e.STARTED_ON || new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'),
      },
    },
  },
};

process.stdout.write(JSON.stringify(doc, null, 2) + '\n');
