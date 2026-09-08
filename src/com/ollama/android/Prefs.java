package com.ollama.android;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 集中管理用户设置（SharedPreferences 封装）。
 * 设置项：GPU 后端、监听地址与端口、日志收起状态，
 * 以及 ollama 全部可用数字参数（服务端环境变量 OLLAMA_* 与对话采样参数 options）。
 * 数字参数统一以字符串存储：留空 = 不设置（使用 ollama 默认值）。
 * 旧版本以 int 存储的 num_gpu/num_ctx/num_thread/port 会自动迁移为字符串。
 */
public final class Prefs {

    public static final String GPU_CPU = "cpu";
    public static final String GPU_VULKAN = "vulkan";
    public static final String GPU_OPENCL = "opencl";

    private static final String FILE = "ollama_prefs";
    private static final String KEY_GPU_BACKEND = "gpu_backend";
    private static final String KEY_HOST = "host";
    private static final String KEY_LOG_COLLAPSED = "log_collapsed";

    // ---- ollama 服务端环境变量（OLLAMA_*） ----
    // 注：服务端口与旧设置共用键 "port"，注入时映射为 OLLAMA_PORT，
    // 保证界面端口与 bindAddress 一致。
    public static final String[] SERVER_ENV_KEYS = {
            "OLLAMA_NUM_PARALLEL",
            "OLLAMA_MAX_LOADED_MODELS",
            "OLLAMA_MAX_QUEUE",
            "OLLAMA_CONTEXT_LENGTH",
            "OLLAMA_MAX_TRANSFER_STREAMS",
            "port",               // -> OLLAMA_PORT
            "OLLAMA_NUM_THREADS"
    };

    // ---- 对话采样参数（/api/chat options） ----
    public static final String[] OPTION_KEYS = {
            "num_ctx", "num_predict", "seed", "top_k", "repeat_last_n",
            "mirostat", "num_gpu", "num_thread", "num_batch", "num_gqa",
            "temperature", "top_p", "repeat_penalty",
            "mirostat_tau", "mirostat_eta", "tfs_z"
    };

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---------------- 通用字符串存取（数字参数，空=默认） ----------------

    /**
     * 读取字符串值。旧版本同键名存的是 int，这里自动迁移：
     * getString 抛 ClassCastException 时退回读 getInt 并转为字符串。
     */
    public static String getStr(Context c, String key) {
        SharedPreferences sp = sp(c);
        if (!sp.contains(key)) {
            return "";
        }
        try {
            return sp.getString(key, "");
        } catch (ClassCastException e) {
            return String.valueOf(sp.getInt(key, 0));
        }
    }

    public static void setStr(Context c, String key, String value) {
        sp(c).edit().putString(key, value == null ? "" : value.trim()).apply();
    }

    /** 所有非空数字参数按 key:value 追加到 StringBuilder（用于 options JSON 或 env）。 */
    public static void appendNonEmpty(StringBuilder sb, String key, String value) {
        String v = value == null ? "" : value.trim();
        if (!v.isEmpty()) {
            sb.append(key).append('=').append(v).append('\n');
        }
    }

    // ---------------- GPU 后端 ----------------

    public static String gpuBackend(Context c) {
        return sp(c).getString(KEY_GPU_BACKEND, GPU_CPU);
    }

    public static void setGpuBackend(Context c, String v) {
        sp(c).edit().putString(KEY_GPU_BACKEND, v).apply();
    }

    // ---------------- 旧设置项（保留方法名，内部改读字符串键） ----------------

    /** GPU 层数（-1 表示自动）。 */
    public static int numGpu(Context c) {
        return parseInt(getStr(c, "num_gpu"), -1);
    }

    public static void setNumGpu(Context c, int v) {
        setStr(c, "num_gpu", String.valueOf(v));
    }

    /** 上下文长度（token）。 */
    public static int numCtx(Context c) {
        return parseInt(getStr(c, "num_ctx"), 4096);
    }

    public static void setNumCtx(Context c, int v) {
        setStr(c, "num_ctx", String.valueOf(v));
    }

    /** 线程数（0 表示自动）。 */
    public static int numThread(Context c) {
        return parseInt(getStr(c, "num_thread"), 0);
    }

    public static void setNumThread(Context c, int v) {
        setStr(c, "num_thread", String.valueOf(v));
    }

    public static String host(Context c) {
        return sp(c).getString(KEY_HOST, "127.0.0.1");
    }

    public static void setHost(Context c, String v) {
        sp(c).edit().putString(KEY_HOST, v).apply();
    }

    public static int port(Context c) {
        return parseInt(getStr(c, "port"), 11434);
    }

    public static void setPort(Context c, int v) {
        setStr(c, "port", String.valueOf(v));
    }

    /** 日志面板是否处于整块收起状态（默认展开）。 */
    public static boolean logCollapsed(Context c) {
        return sp(c).getBoolean(KEY_LOG_COLLAPSED, false);
    }

    public static void setLogCollapsed(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_LOG_COLLAPSED, v).apply();
    }

    /** 组装成 ollama 可用的 host:port 形式。 */
    public static String bindAddress(Context c) {
        return host(c) + ":" + port(c);
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
