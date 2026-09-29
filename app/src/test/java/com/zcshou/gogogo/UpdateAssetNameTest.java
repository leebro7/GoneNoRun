package com.zcshou.gogogo;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 自更新路径安全性的回归测试。
 *
 * 背景：更新流程会用发布页返回的资产名参与 new File(dir, name) 拼接。
 * 即使当前实现是由本地拼接而成，也不能依赖「上游数据一定干净」这一假设
 * （零信任原则 1）。本测试固化 MainActivity.isSafeAssetFileName() 的判定契约，
 * 防止后续重构无意间放开路径穿越。
 *
 * 说明：MainActivity.isSafeAssetFileName 为包级可见的静态方法，与本测试同包，
 * 因此可直接调用；该测试在 JVM 单元测试中运行，无需 Android 设备。
 */
public class UpdateAssetNameTest {

    private static boolean safe(String name) {
        return MainActivity.isSafeAssetFileName(name);
    }

    @Test
    public void acceptsWellFormedReleaseAssetNames() {
        assertTrue(safe("Go_1.12.3_arm64-v8a_release.apk"));
        assertTrue(safe("Go_1.13.0_arm64-v8a_release.apk"));
        assertTrue(safe("Go_2.0.0-beta1_arm64-v8a_release.apk"));
    }

    @Test
    public void rejectsPathTraversal() {
        assertFalse(safe("../../etc/passwd"));
        assertFalse(safe(".."));
        assertFalse(safe("foo/../../bar.apk"));
        assertFalse(safe("..\\..\\windows\\system32"));
    }

    @Test
    public void rejectsPathSeparators() {
        assertFalse(safe("sub/dir/app.apk"));
        assertFalse(safe("sub\\dir\\app.apk"));
        assertFalse(safe("/absolute/path.apk"));
    }

    @Test
    public void rejectsEmptyAndOversized() {
        assertFalse(safe(""));
        assertFalse(safe(null));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 129; i++) {
            big.append('a');
        }
        assertFalse(safe(big.toString()));
    }

    @Test
    public void rejectsUnexpectedCharacters() {
        assertFalse(safe("app apk"));
        assertFalse(safe("app;rm -rf.apk"));
        assertFalse(safe("app$(id).apk"));
        assertFalse(safe("app%2e%2e%2f.apk"));
        assertFalse(safe("应用.apk"));
    }

    @Test
    public void rejectsNonHttpsOrMalformedHashInputs() {
        // SHA-256 解析必须有值且为 64 位十六进制
        assertTrue(MainActivity.parseSha256FromReleaseBody(
                "SHA-256: " + "a".repeat(64)) != null);
        assertTrue(MainActivity.parseSha256FromReleaseBody(
                "SHA-256: " + "A".repeat(64)) != null);   // 大小写不敏感
        assertTrue(MainActivity.parseSha256FromReleaseBody("no hash here") == null);
        assertTrue(MainActivity.parseSha256FromReleaseBody(null) == null);
        assertTrue(MainActivity.parseSha256FromReleaseBody("SHA-256: abc") == null);
        assertTrue(MainActivity.parseSha256FromReleaseBody(
                "SHA-256: " + "a".repeat(63)) == null);
    }
}
