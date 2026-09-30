package com.zcshou.utils;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * {@link PermissionGate} 判定表的回归测试。
 *
 * 背景见 PermissionGate 的类注释：欢迎页原先用「跨次累积的静态列表下标」去索引
 * 系统回调数组，长度不一致时越界、错位时误报；并且把 Android 12+ 的「仅大致位置」
 * 和永久拒绝都归为同一句「权限不足」。本测试把这些分支固化成显式契约。
 *
 * 本测试不依赖 Android 设备：PermissionGate 无任何 Android 依赖。
 */
public class PermissionGateTest {

    private static final String FINE = "android.permission.ACCESS_FINE_LOCATION";
    private static final String COARSE = "android.permission.ACCESS_COARSE_LOCATION";
    private static final int GRANTED = PermissionGate.PERMISSION_GRANTED;
    /** 等价于 PackageManager.PERMISSION_DENIED。 */
    private static final int DENIED = -1;

    private static PermissionGate.Outcome evaluate(String[] requested, int[] grantResults,
                                                   String... askable) {
        Set<String> canAskAgain = new HashSet<>(Arrays.asList(askable));
        return PermissionGate.evaluate(requested, grantResults, FINE, COARSE, canAskAgain::contains);
    }

    @Test
    public void preciseGrantedIsAccepted() {
        assertEquals(PermissionGate.Outcome.ALL_GRANTED,
                evaluate(new String[]{FINE, COARSE}, new int[]{GRANTED, GRANTED}, FINE, COARSE));
    }

    @Test
    public void coarseDeniedButPreciseGrantedIsStillAccepted() {
        // 通过条件只看精确位置：COARSE 被拒不影响判定
        assertEquals(PermissionGate.Outcome.ALL_GRANTED,
                evaluate(new String[]{FINE, COARSE}, new int[]{GRANTED, DENIED}, FINE));
    }

    @Test
    public void approximateOnlyIsItsOwnOutcome() {
        // Android 12+ 用户选择「大致位置」：FINE 被拒、COARSE 授予。
        // 原实现按「有一项被拒即失败」处理，用户会看到无法解决的「权限不足」。
        assertEquals(PermissionGate.Outcome.APPROXIMATE_ONLY,
                evaluate(new String[]{FINE, COARSE}, new int[]{DENIED, GRANTED}, FINE));
    }

    @Test
    public void approximateOnlyWinsOverPermanentlyDenied() {
        // 手里确实有权限时，应引导「改为精确」，而不是断言「你已永久拒绝」
        assertEquals(PermissionGate.Outcome.APPROXIMATE_ONLY,
                evaluate(new String[]{FINE, COARSE}, new int[]{DENIED, GRANTED}));
    }

    @Test
    public void deniedButAskableIsRetryable() {
        assertEquals(PermissionGate.Outcome.RETRYABLE,
                evaluate(new String[]{FINE, COARSE}, new int[]{DENIED, DENIED}, FINE, COARSE));
    }

    @Test
    public void deniedAndNotAskableIsBlocked() {
        // 第二次拒绝 / 「不再询问」后系统不再弹框：必须给出跳转设置的出口
        assertEquals(PermissionGate.Outcome.BLOCKED,
                evaluate(new String[]{FINE, COARSE}, new int[]{DENIED, DENIED}));
    }

    @Test
    public void mixedDenialBlocksWhenTheUnaskableOneIsDenied() {
        assertEquals(PermissionGate.Outcome.BLOCKED,
                evaluate(new String[]{FINE, COARSE}, new int[]{DENIED, DENIED}, COARSE));
    }

    @Test
    public void nullPredicateIsConservative() {
        assertEquals(PermissionGate.Outcome.BLOCKED,
                PermissionGate.evaluate(new String[]{FINE}, new int[]{DENIED}, FINE, COARSE, null));
    }

    @Test
    public void shorterResultsAreUnusableNotGranted() {
        // 回归用例：原实现在此处读取越界（ArrayIndexOutOfBoundsException）。
        // 判定结果必须既不是「通过」，也不能崩。
        assertEquals(PermissionGate.Outcome.UNUSABLE,
                evaluate(new String[]{FINE, COARSE}, new int[]{GRANTED}, FINE, COARSE));
    }

    @Test
    public void emptyResultsAreUnusable() {
        assertEquals(PermissionGate.Outcome.UNUSABLE,
                evaluate(new String[]{FINE, COARSE}, new int[0], FINE, COARSE));
    }

    @Test
    public void nullArraysAreUnusable() {
        assertEquals(PermissionGate.Outcome.UNUSABLE,
                PermissionGate.evaluate(null, null, FINE, COARSE, p -> true));
        assertEquals(PermissionGate.Outcome.UNUSABLE,
                PermissionGate.evaluate(new String[0], new int[0], FINE, COARSE, p -> true));
    }
}
