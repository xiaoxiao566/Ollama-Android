package com.ollama.android;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 把日志镜像写入外部存储根目录的 ollama-log/ 文件夹
 * （例如 /storage/emulated/0/ollama-log/0x_20260908220610.log），
 * 方便用户直接把日志文件反馈给开发者排查问题。
 *
 * 命名规则：0x_ + ollama 服务启动时间（全数字，具体到秒）。
 *  - 文件夹由 App 启动时创建（ensureDir）
 *  - 日志文件在 ollama 服务启动那一刻创建（init，取当时的系统时间命名）
 *
 * 权限要求：
 *  - Android 11+（API 30+）：需要“所有文件访问”（MANAGE_EXTERNAL_STORAGE）
 *  - Android 10 及以下：需要 WRITE_EXTERNAL_STORAGE 运行时权限
 */
public final class LogFile {

    private static final String TAG = "LogFile";
    private static final String DIR_NAME = "ollama-log";

    private static volatile File sDir;
    private static volatile Writer sWriter;
    private static volatile boolean sWritable;

    private LogFile() {}

    /** 是否已具备写外部存储根目录的权限。 */
    public static boolean hasPermission(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return c.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 创建 ollama-log 目录（App 启动时调用，幂等）；无权限时返回 false。 */
    public static synchronized boolean ensureDir(Context c) {
        if (sDir != null) {
            return true;
        }
        if (!hasPermission(c)) {
            return false;
        }
        try {
            File root = Environment.getExternalStorageDirectory();
            File dir = new File(root, DIR_NAME);
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "创建目录失败: " + dir);
                return false;
            }
            sDir = dir;
            return true;
        } catch (Exception e) {
            Log.w(TAG, "创建日志目录失败", e);
            return false;
        }
    }

    /**
     * 打开本次 ollama 服务的日志文件（服务启动时调用）。
     * 文件命名为 0x_<服务启动时间 yyyyMMddHHmmss>.log；已打开过则保持原文件，
     * 保证“一次服务启动对应一个日志文件”。
     */
    public static synchronized void init(Context c) {
        if (sWritable && sWriter != null) {
            return;
        }
        if (!ensureDir(c)) {
            return;
        }
        try {
            String name = "0x_"
                    + new SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(new Date())
                    + ".log";
            sWriter = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(new File(sDir, name), true), StandardCharsets.UTF_8));
            sWritable = true;
        } catch (Exception e) {
            Log.w(TAG, "初始化日志文件失败", e);
            sWritable = false;
        }
    }

    /** 追加一行（带时间戳）；写入失败自动停用落盘，避免反复报错。 */
    public static synchronized void write(String line) {
        if (!sWritable || sWriter == null) {
            return;
        }
        try {
            sWriter.write("[" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + "] " + line + "\n");
            sWriter.flush();
        } catch (IOException e) {
            Log.w(TAG, "写日志失败", e);
            sWritable = false;
        }
    }

    /** 当前日志目录，未启用时为 null。 */
    public static String getDirPath() {
        return sDir != null ? sDir.getAbsolutePath() : null;
    }

    public static boolean isWritable() {
        return sWritable;
    }

    private static void close() {
        if (sWriter != null) {
            try { sWriter.close(); } catch (IOException ignored) {}
            sWriter = null;
        }
    }
}
