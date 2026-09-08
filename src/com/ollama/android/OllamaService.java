package com.ollama.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * 前台服务：常驻运行 ollama serve，并持续采集其输出作为日志广播。
 */
public class OllamaService extends Service {

    public static final String ACTION_LOG = "com.ollama.android.LOG";
    public static final String ACTION_STATUS = "com.ollama.android.STATUS";
    public static final String EXTRA_LINE = "line";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_ERROR = "error";

    public static final String STATE_STARTING = "starting";
    public static final String STATE_RUNNING = "running";
    public static final String STATE_STOPPED = "stopped";
    public static final String STATE_ERROR = "error";

    private static final String TAG = "OllamaService";
    private static final String CHANNEL_ID = "ollama_service";
    private static final int NOTIFICATION_ID = 1;
    private static final int MAX_LOG_LENGTH = 60000;

    private static final StringBuilder sLog = new StringBuilder();
    private static volatile boolean sProcessRunning = false;

    private Process process;
    private Thread readerThread;
    private OllamaRunner runner;

    // ---------------- 供 Activity 读取的静态接口 ----------------

    public static synchronized String getLog() {
        return sLog.toString();
    }

    public static boolean isRunning() {
        return sProcessRunning;
    }

    /** 清空日志缓冲（供界面「清空」按钮调用）；已落盘的日志文件保留。 */
    public static synchronized void clearLog() {
        sLog.setLength(0);
        LogFile.write("[界面日志缓冲已清空，文件中的历史日志保留]");
    }

    // ---------------- 服务生命周期 ----------------

    @Override
    public void onCreate() {
        super.onCreate();
        runner = new OllamaRunner(this);
        LogFile.init(this);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification("正在启动 Ollama 服务…"));
        if (process == null) {
            startServer();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopServer();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------- 核心：启动服务 ----------------

    private void startServer() {
        logStartupConfig();
        appendLog("== 启动 ollama serve ==");
        appendLog(runner.describeCommand());

        try {
            ProcessBuilder pb = runner.buildServeCommand();
            setState(STATE_STARTING);
            process = pb.start();
            setRunning(true);
            appendLog("进程已创建 (pid " + getPidOf(process) + ")，等待服务就绪…");

            readerThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    drainOutput(process);
                }
            }, "ollama-log-reader");
            readerThread.start();
        } catch (IOException e) {
            Log.e(TAG, "启动失败", e);
            setRunning(false);
            appendLog("[错误] 启动失败: " + e.getMessage());
            setState(STATE_ERROR, e.getMessage());
            stopForeground(true);
            stopSelf();
        }
    }

    private void logStartupConfig() {
        String gpu = Prefs.gpuBackend(this);
        String gpuDesc;
        if (Prefs.GPU_VULKAN.equals(gpu)) {
            gpuDesc = "Vulkan" + (runner.hasVulkanBackend() ? "（已内置后端）" : "（未内置后端，日志将看不到 vulkan 设备，会回退 CPU）");
        } else if (Prefs.GPU_OPENCL.equals(gpu)) {
            gpuDesc = "OpenCL（现代 llama.cpp 已移除该后端，实际回退 CPU）";
        } else {
            gpuDesc = "CPU";
        }
        appendLog("== 运行配置 ==");
        appendLog("GPU 后端: " + gpuDesc);
        appendLog("监听地址: " + Prefs.bindAddress(this));
        appendLog("线程数: " + (Prefs.numThread(this) > 0 ? Prefs.numThread(this) : "自动（大核）"));
        appendLog("上下文长度: " + Prefs.numCtx(this));
        appendLog("GPU 层数: " + (Prefs.numGpu(this) < 0 ? "自动" : Prefs.numGpu(this)));
    }

    private void stopServer() {
        setRunning(false);
        if (process != null) {
            appendLog("== 停止 ollama serve ==");
            try { process.destroy(); } catch (Exception ignored) {}
            process = null;
        }
        if (readerThread != null) {
            readerThread = null;
        }
        setState(STATE_STOPPED);
    }

    /** 持续读取子进程 stdout（含合并后的 stderr），逐行写入日志。 */
    private void drainOutput(Process p) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                appendLog(line);
                if (line.contains("msg=\"server listening\"") || line.contains("Listening on")) {
                    setState(STATE_RUNNING);
                }
            }
        } catch (IOException e) {
            appendLog("[日志读取结束] " + e.getMessage());
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) {}
            }
            int exit = -1;
            try { exit = p.exitValue(); } catch (IllegalThreadStateException e2) { /* 仍在运行 */ }
            setRunning(false);
            appendLog("== ollama serve 已退出 (exit " + exit + ") ==");
            setState(STATE_STOPPED);
        }
    }

    private long getPidOf(Process p) {
        try {
            java.lang.reflect.Field f = p.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            return f.getLong(p);
        } catch (Throwable t) {
            return -1;
        }
    }

    // ---------------- 日志采集与广播 ----------------

    private void appendLog(String line) {
        boolean truncated = false;
        synchronized (OllamaService.class) {
            sLog.append(line).append('\n');
            if (sLog.length() > MAX_LOG_LENGTH) {
                sLog.delete(0, sLog.length() - MAX_LOG_LENGTH);
                truncated = true;
            }
        }
        // 镜像到外部存储 ollama-log/ 目录下的日志文件，便于用户反馈问题
        LogFile.write(line);
        Log.i(TAG, line);
        Intent i = new Intent(ACTION_LOG);
        i.putExtra(EXTRA_LINE, line);
        i.setPackage(getPackageName());
        sendBroadcast(i);
        if (truncated) {
            Intent t = new Intent(ACTION_LOG);
            t.putExtra(EXTRA_LINE, "[日志已滚动截断]");
            t.setPackage(getPackageName());
            sendBroadcast(t);
        }
    }

    private void setState(String state) {
        setState(state, null);
    }

    private void setState(String state, String error) {
        Intent i = new Intent(ACTION_STATUS);
        i.putExtra(EXTRA_STATE, state);
        if (error != null) {
            i.putExtra(EXTRA_ERROR, error);
        }
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    private void setRunning(boolean running) {
        sProcessRunning = running;
    }

    // ---------------- 通知 ----------------

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Ollama 服务", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Ollama 推理服务运行状态");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String text) {
        Intent launch = new Intent(this, MainActivity.class);
        launch.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle("Ollama")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pi)
                .setOngoing(true);
        return b.build();
    }
}