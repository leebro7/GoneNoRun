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
GoGoGo (影梭) - A mock location app without root on Android 8.0+
<br/>
<b>This repository is a hardened baseline derived from <a href="https://github.com/ZCShou/GoGoGo">ZCShou/GoGoGo</a>, not an upstream release.</b>
</div>

---

## What this repository is

GoGoGo is a mock-location tool for Android built on the Android debugging API plus Baidu Map/Location SDKs, with a joystick for simulated movement. This repository keeps the same features but fixes a set of high-severity flaws in the historical codebase and turns the security controls into enforced CI gates.

**Why a separate repository:** the upstream Android release signing key was committed publicly (`keystore/GoGoGo.jks`, 2022-06-18, commit `bf70ec1`) together with its hard-coded password in `app/build.gradle`, and the `*.jks` ignore rule had been commented out. Because Android treats the APK signing certificate as the update trust anchor, anyone holding that key can sign an APK that existing devices accept as a legitimate update. A private key that has been public cannot be made trustworthy again by configuration, so this repository uses a brand-new signing identity and treats the old certificate as revoked.

Old (revoked) certificate SHA-256: `6eec4ddd865b85482bd5a97ef4ceaa14dded5c3a6cdcbffd4eccb78c54eff081`

## Hardening summary

| Area | Controls |
|---|---|
| Signing & keys | No key material in git; no plaintext credentials in build scripts; unsigned rather than failing when credentials are absent; release credentials gated behind the `production` environment; single Gradle signing pass |
| CI/CD supply chain | All actions pinned to commit SHAs; top-level `permissions: contents: read`; no `pull_request_target`; releases only on `tags: ['v*']`; PR builds never touch secrets; vendored binaries covered by an independent SHA-256 manifest |
| App runtime | Least-privilege permissions (11 declarations removed); cleartext traffic denied by default; backup/device-transfer disabled; FileProvider narrowed; logs moved to internal storage |
| Self-update | `UpdateVerifier` enforces SHA-256 **and** signing-certificate checks, aborting on either failure; exact asset-name matching; path-traversal rejection; HTTPS only |

See [`docs/SECURITY-HARDENING.md`](docs/SECURITY-HARDENING.md) and [`docs/AUDIT-REMEDIATION.md`](docs/AUDIT-REMEDIATION.md) (both in Chinese) for evidence and the list of controls that must not be reverted.

## Build

Requires **JDK 17** and the **Android SDK** (`compileSdk 36`, `buildToolsVersion 36.0.0`).

```bash
./gradlew assembleDebug            # debug build, no credentials needed

export GOGOGO_KEYSTORE_FILE=/path/to/release.p12
export GOGOGO_KEYSTORE_PASSWORD='<strong passphrase>'
export GOGOGO_KEY_PASSWORD='<same as above>'
export GOGOGO_KEY_ALIAS=gogogo-release
./gradlew assembleRelease          # release build, signed
```

## Signing identity

| Item | Value |
|---|---|
| DN | `CN=GoGoGo_Release, O=GoGoGo, C=CN` |
| Certificate SHA-256 | `84:9C:20:2C:7B:0B:24:C2:1C:78:A2:33:0E:45:73:99:C2:B6:A3:A7:79:E0:A2:5D:20:E7:DA:0A:CF:89:85:5E` |
| SPKI SHA-256 | `ZIXuS0qHV1ZBk/hGrnazUWKdHEC5YzbR/Gc39uO+P0w=` |

```bash
apksigner verify --print-certs your.apk | grep 'certificate SHA-256'
```

Any APK signed with `6eec4ddd…` should be treated as untrusted.

## Known limitations

Real-device regression is still pending (mock location, overlay joystick, Baidu SDK under cleartext-denied policy, full self-update path). The in-app update check still points at the upstream repository, so automatic updates are effectively disabled until it is repointed here. The provenance document is self-attested by the release key and is not equivalent to Sigstore keyless or an independent KMS identity.

## License

GPL-3.0-only © ZCShou (upstream). The hardening changes in this repository are released under the same license.

[中文说明请见 README.md](./README.md)
