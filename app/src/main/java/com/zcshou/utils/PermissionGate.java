package com.zcshou.utils;

import java.util.function.Predicate;

/**
 * 运行时权限申请结果的判定（纯逻辑，不含任何 Android 依赖）。
 *
 * <h3>要解决的问题</h3>
 * 欢迎页原实现有三处缺陷，实机表现为「授予了权限仍然提示『权限不足，请授予相关权限』，
 * 且用户没有任何可操作的出路」：
 *
 * <ol>
 *   <li><b>越界/错位</b>：请求列表是跨次累积且从不清理的静态字段，而回调里用该列表的
 *       下标去索引系统的 {@code grantResults}。二者长度不一致时读取越界
 *       （{@code ArrayIndexOutOfBoundsException}），长度一致但内容错位时会误报拒绝。</li>
 *   <li><b>永久拒绝无出口</b>：被拒绝两次（或选择「不再询问」）后系统不再弹框，
 *       只 toast 一句提示，用户被永久锁在欢迎页。</li>
 *   <li><b>Android 12+「仅大致位置」被当成拒绝</b>：用户在授权框选择「大致位置」时
 *       只有 COARSE 被授予、FINE 被拒，原实现按「全部权限都必须授予」判定，
 *       于是提示权限不足 —— 而正确处置是引导用户改为「精确位置」。</li>
 * </ol>
 *
 * <h3>为什么单独成类</h3>
 * 本类刻意不 import 任何 Android 类，因此可以在**没有 Android SDK 的环境**里用
 * {@code javac} 直接编译并运行判定表测试（见 {@code PermissionGateTest}）。
 * {@code PackageManager.PERMISSION_GRANTED} 自 API 1 起恒为 0，这里以常量复刻，
 * 从而保住这一性质。
 *
 * <h3>判定表</h3>
 * <pre>
 *   回调数据不可用（数组为空/长度不一致）        -> UNUSABLE         （不得据此认为已授权）
 *   精确位置已授予，或本次申请无任何拒绝项        -> ALL_GRANTED
 *   仅「大致位置」被授予（精确位置被拒）          -> APPROXIMATE_ONLY
 *   存在被拒绝、且系统不再弹框的权限              -> BLOCKED           （最坏情况优先）
 *   有拒绝项，但每一项都还能再次弹框              -> RETRYABLE
 * </pre>
 *
 * 注意 APPROXIMATE_ONLY 优先于 BLOCKED：此时 COARSE 已授予，用户手里确实有权限，
 * 需要的是「改为精确」的引导，而不是「你已永久拒绝」的结论。
 *
 * <h3>判定结果不等于准入结论</h3>
 * 本类只回答「这次申请发生了什么」，**不决定**放不放行 —— 那是调用方的产品策略。
 * 本应用的策略是「取得合适的权限就放行，不当拦路虎」：精确与大致位置都算合适，
 * 一项都没拿到也只降级功能、不阻断进入。因此 Outcome 的用途是选择提示文案
 * （以及永久拒绝时告诉用户去哪里开启），而不是把用户堵在欢迎页。
 */
public final class PermissionGate {

    /** 等价于 {@code android.content.pm.PackageManager.PERMISSION_GRANTED}（Android 自 API 1 起恒为 0）。 */
    public static final int PERMISSION_GRANTED = 0;

    /** 一次权限申请回调的判定结果。 */
    public enum Outcome {
        /** 满足进入条件：精确位置已授予，或本次申请没有任何拒绝项。 */
        ALL_GRANTED,
        /** 只拿到「大致位置」（Android 12+ 用户选择了「仅大致」），需引导改为精确位置。 */
        APPROXIMATE_ONLY,
        /** 被拒绝，但系统仍会再次弹框，可以直接重新申请。 */
        RETRYABLE,
        /** 已被永久拒绝（二次拒绝 / 「不再询问」），只能去系统设置手动授权。 */
        BLOCKED,
        /** 回调数据不可用（为空或与请求长度不一致）。保守处理：既不算通过，也不断言用户拒绝。 */
        UNUSABLE
    }

    private PermissionGate() {
    }

    /**
     * 判定一次 {@code onRequestPermissionsResult}。
     *
     * @param requested    系统回调参数 {@code permissions} —— <b>不是</b>发起申请时的本地列表。
     *                     用回调返回的数组而非本地缓存，是本类消除「下标错位」的关键。
     * @param grantResults 与 {@code requested} 逐位对应的授权结果
     * @param precise      精确位置权限名（本应用的通过条件）
     * @param approximate  大致位置权限名（用于识别 Android 12+ 的「仅大致位置」）
     * @param canAskAgain  给定权限，系统是否仍会弹出授权框；
     *                     由 {@code Activity.shouldShowRequestPermissionRationale} 提供。
     *                     传 {@code null} 表示无法判断，此时拒绝一律按 BLOCKED 保守处理。
     * @return 判定结果，永不为 {@code null}
     */
    public static Outcome evaluate(String[] requested, int[] grantResults,
                                   String precise, String approximate,
                                   Predicate<String> canAskAgain) {
        if (requested == null || grantResults == null
                || requested.length == 0 || requested.length != grantResults.length) {
            return Outcome.UNUSABLE;
        }

        boolean preciseGranted = false;
        boolean approximateGranted = false;
        boolean anyDenied = false;
        boolean anyDeniedNotAskable = false;

        for (int i = 0; i < requested.length; i++) {
            String permission = requested[i];
            if (grantResults[i] == PERMISSION_GRANTED) {
                if (permission.equals(precise)) {
                    preciseGranted = true;
                } else if (permission.equals(approximate)) {
                    approximateGranted = true;
                }
            } else {
                anyDenied = true;
                if (canAskAgain == null || !canAskAgain.test(permission)) {
                    anyDeniedNotAskable = true;
                }
            }
        }

        if (preciseGranted) {
            return Outcome.ALL_GRANTED;
        }
        if (!anyDenied) {
            // 本次只申请了大致位置且被授予（精确位置已在此前授予、未参与本次申请）
            return Outcome.ALL_GRANTED;
        }
        if (approximateGranted) {
            return Outcome.APPROXIMATE_ONLY;
        }
        /*
         * 最坏情况优先：只要**存在**一项被拒绝且系统不再弹框的权限，就必须走
         * 「去系统设置」这条路。否则会出现这样的死局：精确位置已被永久拒绝
         * （只有设置能改），而大致位置仍可再问 —— 若因此判定为「可重试」，
         * 用户点多少次都只会再次被拒，且永远看不到设置入口。
         */
        return anyDeniedNotAskable ? Outcome.BLOCKED : Outcome.RETRYABLE;
    }
}
