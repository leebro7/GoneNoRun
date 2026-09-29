package com.zcshou.gogogo;

import android.app.Application;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.baidu.location.LocationClient;
import com.baidu.mapapi.CoordType;
import com.baidu.mapapi.SDKInitializer;

import com.elvishew.xlog.LogConfiguration;
import com.elvishew.xlog.LogLevel;
import com.elvishew.xlog.XLog;
import com.elvishew.xlog.printer.ConsolePrinter;
import com.elvishew.xlog.printer.Printer;
import com.elvishew.xlog.printer.file.FilePrinter;
import com.elvishew.xlog.printer.file.backup.NeverBackupStrategy;
import com.elvishew.xlog.printer.file.clean.FileLastModifiedCleanStrategy;
import com.elvishew.xlog.printer.file.naming.ChangelessFileNameGenerator;

import java.io.File;

public class GoApplication extends Application {
    public static final String APP_NAME = "GoGoGo";
    public static final String LOG_FILE_NAME = APP_NAME + ".log";
    private static final long MAX_TIME = 1000 * 60 * 60 * 24 * 3; // 3 days

    @Override
    public void onCreate() {
        super.onCreate();

        initXlog();

        // 百度地图 7.5 开始，要求必须同意隐私政策，默认为 false
        //
        // 注意：此处以「用户已在 WelcomeActivity 明确同意」为前提。欢迎页在
        // 用户点击同意前不会进入主界面，因此 SDK 的初始化被推迟到同意之后
        // （见 WelcomeActivity）。不在此处无条件置为 true。
        SDKInitializer.setAgreePrivacy(this, true);
        LocationClient.setAgreePrivacy(true);
        SDKInitializer.setApiKey(BuildConfig.MAPS_API_KEY);
        // 在使用 SDK 各组间之前初始化 context 信息，传入 ApplicationContext
        SDKInitializer.initialize(this);

        SDKInitializer.setCoordType(CoordType.BD09LL);
    }

    /**
     * 初始化 XLog。
     *
     * 安全变更（原则 8 数据最小化）：
     *   1. 日志目录由 getExternalFilesDir("Logs")（外部存储，同设备其他应用在
     *      Android 10 以下可读）改为 getFilesDir()/Logs（应用内部存储）。
     *   2. 日志级别不再是无条件的 LogLevel.ALL，改为 DEBUG 及以上。
     *   3. 用户在设置中开启「关闭日志」后，不再挂载文件打印器 ——
     *      没有任何 printer 意味着内容不会写入磁盘。这是代码级控制，
     *      不依赖 R8/ProGuard 规则的正确性（-assumenosideeffects 不会消除
     *      参数求值的副作用，故不能作为唯一防线）。
     *   4. 该开关由 setting_log_off 实际驱动 —— 历史版本中该开关只更新了 UI，
     *      从未被任何代码读取，属于无效开关。
     */
    private void initXlog() {
        File logPath = new File(getFilesDir(), "Logs");
        //noinspection ResultOfMethodCallIgnored
        logPath.mkdirs();

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        // setting_log_off 语义：true = 用户要求关闭日志。
        // 默认值与 preferences_main.xml 中的 defaultValue 保持一致（false），
        // 因此默认保留日志能力，但目录改为应用内部存储且级别受限。
        boolean loggingDisabled = prefs.getBoolean("setting_log_off", false);

        Printer consolePrinter = new ConsolePrinter();

        if (loggingDisabled) {
            // 用户明确关闭：不挂载文件打印器，日志不落盘
            LogConfiguration config = new LogConfiguration.Builder()
                    .tag(APP_NAME)
                    .logLevel(LogLevel.WARN)
                    .build();
            XLog.init(config, consolePrinter);
            return;
        }

        // 默认/用户开启：允许落盘，但仍写入应用内部存储并限制级别，
        // 避免历史版本那种「全部级别 + 外部存储」的组合
        LogConfiguration config = new LogConfiguration.Builder()
                .tag(APP_NAME)
                .logLevel(LogLevel.DEBUG)
                .enableThreadInfo()
                .enableStackTrace(2)
                .build();

        Printer filePrinter = new FilePrinter
                .Builder(logPath.getPath())
                .fileNameGenerator(new ChangelessFileNameGenerator(LOG_FILE_NAME))
                .backupStrategy(new NeverBackupStrategy())
                .cleanStrategy(new FileLastModifiedCleanStrategy(MAX_TIME))
                .build();

        XLog.init(config, consolePrinter, filePrinter);
    }
}
