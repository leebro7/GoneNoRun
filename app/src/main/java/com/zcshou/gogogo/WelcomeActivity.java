package com.zcshou.gogogo;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.method.MovementMethod;
import android.text.style.ClickableSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.elvishew.xlog.XLog;
import com.zcshou.utils.GoUtils;
import com.zcshou.utils.PermissionGate;

import java.util.ArrayList;

public class WelcomeActivity extends AppCompatActivity {
    private static SharedPreferences preferences;
    private static final String KEY_ACCEPT_AGREEMENT = "KEY_ACCEPT_AGREEMENT";
    private static final String KEY_ACCEPT_PRIVACY = "KEY_ACCEPT_PRIVACY";

    private static final int SDK_PERMISSION_REQUEST = 127;

    /**
     * 本次界面生命周期内是否已经发起过定位权限申请。
     *
     * 用途是区分「还没问过」与「问过但被拒绝」：前者应当弹一次系统授权框，
     * 后者绝不能再弹（系统也不会弹）—— 否则用户会被卡在「点了没反应」的循环里。
     * 权限不是拦路虎：拿不到也照样放行，只是相应功能降级。
     */
    private boolean permissionRequested = false;

    private CheckBox checkBox;
    private Boolean mAgreement;
    private Boolean mPrivacy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_welcome);

        // 生成默认参数的值（一定要尽可能早的调用，因为后续有些界面可能需要使用参数）
        PreferenceManager.setDefaultValues(this, R.xml.preferences_main, false);

        Button startBtn = findViewById(R.id.startButton);
        startBtn.setOnClickListener(v -> startMainActivity());

        checkAgreementAndPrivacy();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != SDK_PERMISSION_REQUEST) {
            return;
        }

        /*
         * 判定只依赖系统回调给出的 permissions / grantResults（逐位对应），
         * 不再用本地列表的下标去索引回调数组 —— 那是原实现越界与误判的根源。
         * shouldShowRequestPermissionRationale 在这里是可靠的：它是在**结果回调之后**
         * 判定「还能不能再弹框」，因此不存在「首次申请前恒为 false」的歧义。
         */
        PermissionGate.Outcome outcome = PermissionGate.evaluate(
                permissions,
                grantResults,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                this::shouldShowRequestPermissionRationale);

        /*
         * 判定结果的用途是「怎么告诉用户」，**不是**「放不放行」。
         *
         * 放行原则（用户明确要求：取得合适的权限就放行，不当拦路虎）：
         *   精确位置、大致位置都算取得了合适的权限，直接放行、不打扰；
         *   一项都没拿到也只做非阻塞提示，绝不弹模态框把用户堵在欢迎页。
         */
        switch (outcome) {
            case ALL_GRANTED:
            case APPROXIMATE_ONLY:
                // 「精确」或「大致」都已满足；后者的精度差异不在这里唠叨
                XLog.d("定位权限申请结果：" + outcome);
                break;
            case RETRYABLE:
                GoUtils.DisplayToast(this, getResources().getString(R.string.app_error_permission));
                break;
            case BLOCKED:
                // 永久拒绝只提示一句（含去哪里开启），不弹框、不阻断进入
                GoUtils.DisplayToast(this, getResources().getString(R.string.app_permission_blocked));
                break;
            case UNUSABLE:
            default:
                // 回调数据不可用：既不能当成已授权，也不能断言用户拒绝
                XLog.e("权限回调数据不可用: permissions=" + permissions.length
                        + " grantResults=" + grantResults.length);
                GoUtils.DisplayToast(this, getResources().getString(R.string.app_error_permission));
                break;
        }
    }

    private void checkDefaultPermissions() {
        /*
         * 待申请列表是**局部变量**，每次现算现用。
         *
         * 原实现是一个 static final 的 ArrayList，只增不减：每次进入欢迎页都把
         * 「仍未授权」的权限再 add 一遍，列表随尝试次数不断变长；而回调里又用这个
         * 列表的下标去索引系统回调的 grantResults。长度不一致时读取越界
         * （ArrayIndexOutOfBoundsException），长度一致但内容错位时误报「权限不足」。
         * 现在回调只信任系统返回的数组（见 PermissionGate.evaluate），
         * 本地列表不再承担任何跨调用的状态。
         */
        ArrayList<String> reqPermissions = new ArrayList<>();

        // 定位精确位置
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            reqPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            reqPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }

        /*
         * 权限最小化（原则 3 / F-05）
         *
         * 此处原先还会申请「外部存储读取」与「电话状态」两项权限，现均已连同
         * AndroidManifest.xml 中的声明一并移除，原因分别是：
         *   - 外部存储读取：日志已迁入应用内部存储，不再需要
         *   - 电话状态：代码中从未使用，属历史遗留
         *
         * 运行时申请未在清单中声明的权限必然被系统拒绝，且会弹出无意义的授权
         * 对话框。因此这里只申请核心功能真正需要的前景定位权限。
         */

        if (reqPermissions.isEmpty()) {
            // 已全部授权：等待用户点击「进入应用」；此处不再缓存结论，
            // 是否放行由 startMainActivity() 现查现判
            return;
        }

        // 记下「已经问过」：之后即使用户拒绝，也只会放行 + 提示，不再反复弹框
        permissionRequested = true;
        requestPermissions(reqPermissions.toArray(new String[0]), SDK_PERMISSION_REQUEST);
    }

    /**
     * 是否已取得可用的定位权限。
     *
     * 精确与大致都算数：Android 12+ 的授权框允许用户只选「大致位置」，
     * 那是用户的正当选择 —— 只给大致位置也要放行，不能因此把人挡在门外。
     */
    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void enterMainActivity() {
        Intent intent = new Intent(WelcomeActivity.this, MainActivity.class);
        startActivity(intent);
        WelcomeActivity.this.finish();
    }

    private void startMainActivity() {
        if (!checkBox.isChecked()) {
            GoUtils.DisplayToast(this, getResources().getString(R.string.app_error_agreement));
            return;
        }

        if (!GoUtils.isNetworkAvailable(this)) {
            GoUtils.DisplayToast(this, getResources().getString(R.string.app_error_network));
            return;
        }

        if (!GoUtils.isGpsOpened(this)) {
            GoUtils.DisplayToast(this, getResources().getString(R.string.app_error_gps));
            return;
        }

        /*
         * 权限不拦路（每次点「进入应用」都按系统当前状态现查现判，不缓存结论）：
         *   - 拿到定位权限（精确或大致）          -> 直接放行；
         *   - 一个都没拿到，但本次已经问过了      -> 仍然放行，只做一次非阻塞提示；
         *   - 还没问过                            -> 先弹一次系统授权框。
         * 这样既不会「带伤进入」，也不会出现「点了没反应」的死循环。
         */
        if (hasLocationPermission()) {
            enterMainActivity();
        } else if (permissionRequested) {
            GoUtils.DisplayToast(this, getResources().getString(R.string.app_permission_missing));
            enterMainActivity();
        } else {
            checkDefaultPermissions();
        }
    }

    private void doAcceptation() {
        if (mAgreement && mPrivacy) {
            checkBox.setChecked(true);
            checkDefaultPermissions();
        } else {
            checkBox.setChecked(false);
        }
        //实例化Editor对象
        SharedPreferences.Editor editor = preferences.edit();
        //存入数据
        editor.putBoolean(KEY_ACCEPT_AGREEMENT, mAgreement);
        editor.putBoolean(KEY_ACCEPT_PRIVACY, mPrivacy);
        //提交修改
        editor.apply();
    }

    private void showAgreementDialog() {
        final AlertDialog alertDialog = new AlertDialog.Builder(this).create();
        alertDialog.show();
        alertDialog.setCancelable(false);
        Window window = alertDialog.getWindow();
        if (window != null) {
            window.setContentView(R.layout.user_agreement);
            window.setGravity(Gravity.CENTER);
            window.setWindowAnimations(R.style.DialogAnimFadeInFadeOut);

            TextView tvContent = window.findViewById(R.id.tv_content);
            Button tvCancel = window.findViewById(R.id.tv_cancel);
            Button tvAgree = window.findViewById(R.id.tv_agree);
            SpannableStringBuilder ssb = new SpannableStringBuilder();
            ssb.append(getResources().getString(R.string.app_agreement_content));
            tvContent.setMovementMethod(LinkMovementMethod.getInstance());
            tvContent.setText(ssb, TextView.BufferType.SPANNABLE);

            tvCancel.setOnClickListener(v -> {
                mAgreement = false;

                doAcceptation();

                alertDialog.cancel();
            });

            tvAgree.setOnClickListener(v -> {
                mAgreement = true;

                doAcceptation();

                alertDialog.cancel();
            });
        }
    }

    private void showPrivacyDialog() {
        final AlertDialog alertDialog = new AlertDialog.Builder(this).create();
        alertDialog.show();
        alertDialog.setCancelable(false);
        Window window = alertDialog.getWindow();
        if (window != null) {
            window.setContentView(R.layout.user_privacy);
            window.setGravity(Gravity.CENTER);
            window.setWindowAnimations(R.style.DialogAnimFadeInFadeOut);

            TextView tvContent = window.findViewById(R.id.tv_content);
            Button tvCancel = window.findViewById(R.id.tv_cancel);
            Button tvAgree = window.findViewById(R.id.tv_agree);
            SpannableStringBuilder ssb = new SpannableStringBuilder();
            ssb.append(getResources().getString(R.string.app_privacy_content));
            tvContent.setMovementMethod(LinkMovementMethod.getInstance());
            tvContent.setText(ssb, TextView.BufferType.SPANNABLE);

            tvCancel.setOnClickListener(v -> {
                mPrivacy = false;

                doAcceptation();

                alertDialog.cancel();
            });

            tvAgree.setOnClickListener(v -> {
                mPrivacy = true;

                doAcceptation();

                alertDialog.cancel();
            });
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void checkAgreementAndPrivacy() {
        preferences = getSharedPreferences(KEY_ACCEPT_AGREEMENT, MODE_PRIVATE);
        mPrivacy = preferences.getBoolean(KEY_ACCEPT_PRIVACY, false);
        mAgreement = preferences.getBoolean(KEY_ACCEPT_AGREEMENT, false);

        checkBox = findViewById(R.id.check_agreement);
        // 拦截 CheckBox 的点击事件
        checkBox.setOnTouchListener((v, event) -> {
            if (v instanceof TextView) {
                TextView text = (TextView) v;
                MovementMethod method = text.getMovementMethod();
                if (method != null && text.getText() instanceof Spannable
                        && event.getAction() == MotionEvent.ACTION_UP) {
                    if (method.onTouchEvent(text, (Spannable) text.getText(), event)) {
                        event.setAction(MotionEvent.ACTION_CANCEL);
                    }
                }
            }
            return false;
        });
        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                if (!mPrivacy || !mAgreement) {
                    GoUtils.DisplayToast(this, getResources().getString(R.string.app_error_read));
                    checkBox.setChecked(false);
                }
            } else {
                mPrivacy = false;
                mAgreement = false;
            }
        });

        String str = getString(R.string.app_agreement_privacy);
        SpannableStringBuilder builder = getSpannableStringBuilder(str);

        checkBox.setText(builder);
        checkBox.setMovementMethod(LinkMovementMethod.getInstance());

        if (mPrivacy && mAgreement) {
            checkBox.setChecked(true);
            checkDefaultPermissions();
        } else {
            checkBox.setChecked(false);
        }
    }

    @NonNull
    private SpannableStringBuilder getSpannableStringBuilder(String str) {
        SpannableStringBuilder builder = new SpannableStringBuilder(str);
        ClickableSpan clickSpanAgreement = new ClickableSpan() {
            @Override
            public void onClick(@NonNull View widget) {
                showAgreementDialog();
            }

            @Override
            public void updateDrawState(TextPaint ds) {
                ds.setColor(getResources().getColor(R.color.colorPrimary, WelcomeActivity.this.getTheme()));
                ds.setUnderlineText(false);
            }
        };
        int agreement_start = str.indexOf("《");
        int agreement_end = str.indexOf("》") + 1;
        builder.setSpan(clickSpanAgreement, agreement_start,agreement_end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        ClickableSpan clickSpanPrivacy = new ClickableSpan() {
            @Override
            public void onClick(@NonNull View widget) {
                showPrivacyDialog();
            }

            @Override
            public void updateDrawState(TextPaint ds) {
                ds.setColor(getResources().getColor(R.color.colorPrimary, WelcomeActivity.this.getTheme()));
                ds.setUnderlineText(false);
            }
        };
        int privacy_start = str.indexOf("《", agreement_end);
        int privacy_end = str.indexOf("》", agreement_end) + 1;
        builder.setSpan(clickSpanPrivacy, privacy_start, privacy_end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return builder;
    }
}
