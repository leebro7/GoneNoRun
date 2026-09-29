package com.zcshou.gogogo;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * 自更新链路所需的完整性校验工具（零信任原则 1：永不信任，始终验证）。
 *
 * 两个独立校验，缺一不可：
 *   1. SHA-256 文件哈希 —— 校验下载过程未被篡改（与发布页公布的哈希比对）
 *   2. APK 签名证书比对 —— 校验产物确实由与本应用相同的签名密钥签署
 *
 * 任何一项失败都必须拒绝安装（默认拒绝，而非默认允许）。
 */
final class UpdateVerifier {

    private UpdateVerifier() {
    }

    /** 计算文件 SHA-256，返回小写十六进制；失败返回 null。 */
    static String sha256OfFile(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return toHex(md.digest());
        } catch (Exception e) {
            return null;
        }
    }

    /** 读取已安装应用的签名证书 SHA-256 集合（十六进制）。 */
    private static java.util.Set<String> installedCertHashes(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo info = pm.getPackageInfo(context.getPackageName(),
                    PackageManager.GET_SIGNING_CERTIFICATES);
            return certHashesOf(info);
        } catch (Exception e) {
            return java.util.Collections.emptySet();
        }
    }

    /** 读取指定 APK 文件的签名证书 SHA-256 集合（十六进制）。 */
    private static java.util.Set<String> archiveCertHashes(Context context, File apk) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo info = pm.getPackageArchiveInfo(apk.getAbsolutePath(),
                    PackageManager.GET_SIGNING_CERTIFICATES);
            return certHashesOf(info);
        } catch (Exception e) {
            return java.util.Collections.emptySet();
        }
    }

    private static java.util.Set<String> certHashesOf(PackageInfo info) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (info == null) {
            return out;
        }
        try {
            Signature[] signers;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                SigningInfo si = info.signingInfo;
                if (si == null) {
                    return out;
                }
                // 已轮换密钥时，历史签名也在 apkContentsSigners 之外的 signingCertificateHistory
                signers = si.hasMultipleSigners()
                        ? si.getApkContentsSigners()
                        : si.getSigningCertificateHistory();
            } else {
                //noinspection deprecation
                signers = info.signatures;
            }
            if (signers == null) {
                return out;
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Signature s : signers) {
                out.add(toHex(md.digest(s.toByteArray())));
                md.reset();
            }
        } catch (NoSuchAlgorithmException ignored) {
            // SHA-256 必然存在
        }
        return out;
    }

    /**
     * 校验 APK 是否由与本应用相同的密钥签署。
     *
     * 使用「已安装证书集合」与「待安装 APK 证书集合」求交集，而非直接比较单个
     * 证书：这样在启用 Android 密钥轮换后，新旧证书都能正确通过。
     *
     * @return true 表示存在共同签名证书，允许继续安装
     */
    static boolean isSignedBySameKey(Context context, File apk) {
        java.util.Set<String> installed = installedCertHashes(context);
        java.util.Set<String> candidate = archiveCertHashes(context, apk);
        if (installed.isEmpty() || candidate.isEmpty()) {
            // 任一侧无法取得证书 —— 默认拒绝
            return false;
        }
        for (String h : candidate) {
            if (installed.contains(h)) {
                return true;
            }
        }
        return false;
    }

    /** 恒定时间比较两个十六进制字符串，避免比较过程泄露信息。 */
    static boolean hashEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] x = a.trim().toLowerCase().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] y = b.trim().toLowerCase().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        return Arrays.equals(x, y) && MessageDigest.isEqual(x, y);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
