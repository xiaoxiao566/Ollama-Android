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
        appendLog("──────────────── 本次启动 ────────────────");
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
        // 运算方式 = CPU / CPU+GPU 混合，由 GPU 后端与层数共同决定：
        // 选了 CPU+GPU 或填了层数（-1 / 具体数字）即走混合；否则纯 CPU。
        String backend = Prefs.gpuBackend(this);
        String ng = Prefs.getStr(this, "num_gpu").trim();
        boolean mixBackend = Prefs.GPU_MIX.equals(backend);
        boolean wantGpu = !ng.isEmpty() && !"0".equals(ng);
        boolean gpuAccel = Prefs.GPU_VULKAN.equals(backend) || mixBackend || wantGpu;

        String mode;
        if (mixBackend) {
            if ("-1".equals(ng)) {
                mode = "CPU + GPU 混合（GPU 自动分配层数，装不下自动回退 CPU）";
            } else if (wantGpu) {
                mode = "CPU + GPU 混合（前 " + ng + " 层走 GPU，其余走 CPU）";
            } else {
                mode = "CPU + GPU 混合（未填层数，GPU 自动分配）";
            }
        } else if (wantGpu) {
            mode = "-1".equals(ng)
                    ? "CPU + GPU 混合（GPU 自动分配层数，装不下自动回退 CPU）"
                    : "CPU + GPU 混合（前 " + ng + " 层走 GPU，其余走 CPU）";
        } else {
            mode = "纯 CPU";
        }
        appendLog("== 运行配置 ==");
        appendLog("运算方式: " + mode);
        appendLog("GPU 加速: " + (gpuAccel && runner.hasVulkanBackend()
                ? "已启用" : (gpuAccel ? "未启用（缺少 GPU 后端库，实际纯 CPU）" : "未启用")));
        appendLog("监听地址: " + Prefs.bindAddress(this));
        String thr = Prefs.getStr(this, "OLLAMA_NUM_THREADS").trim();
        appendLog("线程数: " + (thr.isEmpty() ? "自动（限制 ≤4，防发热降频卡顿）" : thr));
        String kv = Prefs.getStr(this, "OLLAMA_KV_CACHE_TYPE").trim();
        appendLog("KV 缓存: " + (kv.isEmpty() ? "q8_0（8bit 量化，内存减半）" : kv));
        String par = Prefs.getStr(this, "OLLAMA_NUM_PARALLEL").trim();
        appendLog("并行度: " + (par.isEmpty() ? "1（内存不足 8GB 时自动限制）" : par));
        appendLog("上下文长度: " + Prefs.numCtx(this));
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
        boolean vulkanDetected = false;
        boolean cpuFallbackWarned = false;
        try {
            reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                appendLog(line);
                String low = line.toLowerCase(java.util.Locale.US);
                // 显式告诉用户 Vulkan 是否真的生效（默认已启用 Vulkan）
                if (!vulkanDetected
                        && (low.contains("vulkan0") || low.contains("ggml_vulkan")
                        || low.contains("ggml_vk") || low.contains("vulkan device"))) {
                    vulkanDetected = true;
                    appendLog("[GPU] 已启用 Vulkan 加速（" + line.trim() + "）");
                    String gpuB = Prefs.gpuBackend(this);
                    if (!Prefs.GPU_VULKAN.equals(gpuB) && !Prefs.GPU_MIX.equals(gpuB)) {
                        appendLog("[GPU] 警告：设置未选 GPU 后端，但引擎仍加载了 Vulkan（环境变量残留？）");
                    }
                }
                if (!cpuFallbackWarned
                        && (Prefs.GPU_VULKAN.equals(Prefs.gpuBackend(this))
                        || Prefs.GPU_MIX.equals(Prefs.gpuBackend(this)))
                        && (low.contains("no suitable") || low.contains("no compatible"))) {
                    cpuFallbackWarned = true;
                    appendLog("[GPU] 未发现可用 Vulkan 设备，已回退 CPU 推理（速度会明显变慢）");
                }
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