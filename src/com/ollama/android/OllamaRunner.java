package com.ollama.android;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 负责把内置的 ollama / llama.cpp 引擎解压到应用私有目录，
 * 并构造一个在 Android SELinux 下能正常启动的进程环境。
 *
 * 关键点（ollama-termux 的适配逻辑）：
 *  1. Android 8+ 不允许直接 exec 应用私有目录下的可执行文件（SELinux 拒绝），
 *     必须通过系统 linker 启动：/system/bin/linker64 <可执行文件> <参数>。
 *  2. ollama 只有读到 TERMUX_VERSION 环境变量才会启用 termux 运行时适配。
 *  3. TERMUX_EXEC__SYSTEM_LINKER_EXEC=force 强制 llama-server 子进程也走 linker。
 *  4. LD_LIBRARY_PATH 首位放 /system/lib64，让 dlopen("libvulkan.so") 命中
 *     Android 系统 Vulkan 加载器（而非 CPU 软渲染的 llvmpipe）。
 *  5. OLLAMA_VULKAN=1 时，ollama 会到 $PREFIX/lib/ollama/vulkan/ 下发现
 *     libggml-vulkan 后端（需内置该 .so 才真正加速）。
 */
public final class OllamaRunner {

    private static final String TAG = "OllamaRunner";
    public static final String HOST = "127.0.0.1";
    public static final int PORT = 11434;

    /** 与 ollama-termux 一致的 TERMUX 版本号标识，仅用于 IsTermux() 判空。 */
    private static final String TERMUX_VERSION = "0.119.0 beta3";
    private static final String SYSTEM_LINKER = "/system/bin/linker64";

    private final Context context;
    private final File prefix;       // 相当于 Termux 的 $PREFIX
    private final File libOllama;    // $PREFIX/lib/ollama
    private final File modelsDir;    // 模型存放目录
    private final File tmpDir;

    public OllamaRunner(Context context) {
        this.context = context;
        this.prefix = context.getFilesDir();
        this.libOllama = new File(prefix, "lib/ollama");
        this.modelsDir = new File(prefix, "models");
        this.tmpDir = new File(prefix, "tmp");
    }

    public File getPrefix() {
        return prefix;
    }

    public File getModelsDir() {
        return modelsDir;
    }

    public File getLibOllama() {
        return libOllama;
    }

    /** Vulkan 后端库是否存在（决定 GPU 加速是否真的可用）。 */
    public boolean hasVulkanBackend() {
        return new File(libOllama, "vulkan/libggml-vulkan.so").exists();
    }

    /** 首次运行时检查是否已经解压过引擎文件。 */
    public boolean isExtracted() {
        return new File(libOllama, "ollama").exists()
                && new File(libOllama, "llama-server").exists();
    }

    /** 解压内置资源，目录结构递归复制，保持 llama.cpp 后端子目录（如 vulkan）。幂等：已存在文件跳过，升级时自动补齐新增后端。 */
    public synchronized void prepare() throws IOException {
        if (!libOllama.exists() && !libOllama.mkdirs()) {
            throw new IOException("无法创建目录: " + libOllama);
        }
        if (!modelsDir.exists() && !modelsDir.mkdirs()) {
            throw new IOException("无法创建目录: " + modelsDir);
        }
        if (!tmpDir.exists() && !tmpDir.mkdirs()) {
            throw new IOException("无法创建目录: " + tmpDir);
        }

        // 主程序：assets/bin/ollama -> $PREFIX/lib/ollama/ollama（仅首次解压）
        File exe = new File(libOllama, "ollama");
        if (!exe.exists()) {
            Log.i(TAG, "解压引擎资源到 " + libOllama.getAbsolutePath());
            copyAsset("bin/ollama", exe);
        }

        // 运行时库：assets/lib/ollama/** -> $PREFIX/lib/ollama/**（递归，跳过已存在文件）
        extractTree("lib/ollama", libOllama);

        ensureExecutable();
        ensureVulkanBridge();
        Log.i(TAG, "资源就绪" + (hasVulkanBackend() ? "（含 Vulkan 后端）" : "（仅 CPU 后端）"));
    }

    /**
     * libvulkan 兼容桥：Android 系统只提供 /system/lib64/libvulkan.so，
     * 个别组件按 libvulkan.so.1 名字 dlopen。这里把系统 Vulkan 加载器复制一份
     * 到引擎目录并命名为 libvulkan.so.1，两种名字都能命中同一个系统加载器
     * （该加载器运行在 root namespace，能访问 /vendor/lib64/hw/ 下的真实 GPU 驱动）。
     */
    private void ensureVulkanBridge() {
        File bridge = new File(libOllama, "libvulkan.so.1");
        if (bridge.exists() && bridge.length() > 0) {
            return;
        }
        String[] candidates = {
                "/system/lib64/libvulkan.so",
                "/vendor/lib64/libvulkan.so",
                "/system/lib/libvulkan.so"
        };
        for (String c : candidates) {
            File src = new File(c);
            if (src.exists()) {
                try {
                    java.io.FileInputStream in = new java.io.FileInputStream(src);
                    java.io.FileOutputStream out = new java.io.FileOutputStream(bridge);
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                    in.close();
                    out.close();
                    Log.i(TAG, "已创建 libvulkan.so.1 桥 -> " + c);
                } catch (IOException e) {
                    Log.w(TAG, "创建 libvulkan.so.1 桥失败: " + e.getMessage());
                }
                return;
            }
        }
        Log.w(TAG, "未找到系统 libvulkan.so，Vulkan 加速可能不可用");
    }

    private void ensureExecutable() {
        for (String name : new String[]{"ollama", "llama-server", "llama-quantize"}) {
            File f = new File(libOllama, name);
            if (f.exists()) {
                f.setExecutable(true, true);
            }
        }
    }

    /** 递归复制 assets 目录树。 */
    private void extractTree(String assetDir, File targetDir) throws IOException {
        String[] entries = context.getAssets().list(assetDir);
        if (entries == null || entries.length == 0) {
            return;
        }
        for (String name : entries) {
            String childAsset = assetDir + "/" + name;
            File childTarget = new File(targetDir, name);

            // 子目录：递归（assets list 无法区分文件/目录，用再 list 试一次判断）
            String[] sub = context.getAssets().list(childAsset);
            if (sub != null && sub.length > 0) {
                if (!childTarget.exists() && !childTarget.mkdirs()) {
                    throw new IOException("无法创建目录: " + childTarget);
                }
                extractTree(childAsset, childTarget);
            } else {
                if (childTarget.exists() && childTarget.length() > 0) {
                    continue;
                }
                copyAsset(childAsset, childTarget);
            }
        }
    }

    private void copyAsset(String assetPath, File dest) throws IOException {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = context.getAssets().open(assetPath);
            if (dest.getParentFile() != null && !dest.getParentFile().exists()) {
                dest.getParentFile().mkdirs();
            }
            out = new FileOutputStream(dest);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ignored) {}
            }
            if (out != null) {
                try { out.close(); } catch (IOException ignored) {}
            }
        }
        if (assetPath.startsWith("bin/") || assetPath.endsWith("server")
                || assetPath.endsWith("quantize")) {
            dest.setExecutable(true, true);
        }
    }

    /** 构造 ollama serve 的启动命令，经系统 linker 启动，并应用用户设置。 */
    public ProcessBuilder buildServeCommand() throws IOException {
        prepare();
        File exe = new File(libOllama, "ollama");
        String gpu = Prefs.gpuBackend(context);
        // 填了 GPU 层数（-1 或具体数字）就自动启用 GPU 加速做 CPU+GPU 混合，
        // 不用再先去设置页选后端；没填层数时按设置页选择（默认 CPU 最稳）。
        String numGpuStr = Prefs.getStr(context, "num_gpu").trim();
        boolean wantGpu = !numGpuStr.isEmpty() && !"0".equals(numGpuStr);
        boolean vulkan = Prefs.GPU_VULKAN.equals(gpu) || wantGpu;

        List<String> cmd = new ArrayList<String>();
        cmd.add(SYSTEM_LINKER);
        cmd.add(exe.getAbsolutePath());
        cmd.add("serve");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        // 关键：llama-server 经 /system/bin/linker64 启动时 /proc/self/exe 指向
        // linker64 本身，llama.cpp 的动态后端发现扫描不到可执行文件目录
        // （GGML_BACKEND_DIR 是编译期写死的 Termux 路径，本机不存在）。
        // 它能扫的第三个位置是“当前工作目录”，而 llama-server 会继承 ollama
        // 的 cwd，所以把工作目录设到 lib/ollama，让 libggml-cpu-*.so 等
        // 后端库能被扫描加载（否则 CPU 后端一个都加载不了，模型必然加载失败）。
        pb.directory(libOllama);
        pb.redirectErrorStream(true); // stderr 合并进 stdout，便于日志展示

        Map<String, String> env = pb.environment();
        env.put("PATH", "/system/bin:/system/xbin:" + libOllama.getAbsolutePath());
        env.put("HOME", prefix.getAbsolutePath());
        env.put("TMPDIR", tmpDir.getAbsolutePath());
        env.put("PREFIX", prefix.getAbsolutePath());
        env.put("TERMUX_VERSION", TERMUX_VERSION);
        env.put("TERMUX_EXEC__SYSTEM_LINKER_EXEC", "force");
        env.put("TERMUX_EXEC__PROC_SELF_EXE", exe.getAbsolutePath());
        // /system/lib64 优先，确保系统 Vulkan loader（真实 GPU）先于任何软渲染库命中
        env.put("LD_LIBRARY_PATH", "/system/lib64:" + libOllama.getAbsolutePath());
        env.put("OLLAMA_HOST", Prefs.bindAddress(context));
        env.put("OLLAMA_MODELS", modelsDir.getAbsolutePath());
        env.put("OLLAMA_NOHISTORY", "1");
        env.put("OLLAMA_NOPRUNE", "1");
        // GPU 后端选择：termux 适配按「变量是否存在」判定启用 Vulkan，
        // 注入 "0" 也会被当成启用，所以选 CPU 时必须彻底移除该变量。
        if (vulkan) {
            env.put("OLLAMA_VULKAN", "1");
        } else {
            env.remove("OLLAMA_VULKAN");
        }
        if (Prefs.GPU_OPENCL.equals(gpu)) {
            // 现代 llama.cpp 已移除 OpenCL 后端；这里仅作标识，实际回退 CPU
            env.put("OLLAMA_OPENCL", "1");
        } else {
            env.remove("OLLAMA_OPENCL");
        }
        // 用户设置的服务端数字参数（留空不注入，使用 ollama 默认值）。
        // 键 "port" 映射为 OLLAMA_PORT，与 UI 端口 / bindAddress 保持一致。
        for (String key : Prefs.SERVER_ENV_KEYS) {
            String v = Prefs.getStr(context, key).trim();
            if (!v.isEmpty()) {
                String envName = "port".equals(key) ? "OLLAMA_PORT" : key;
                env.put(envName, v);
                Log.i(TAG, "注入环境变量 " + envName + "=" + v);
            }
        }

        // ---- 移动端性能默认值（用户未手动设置时才注入，防止推理中途卡死） ----
        // 1) 限制线程数：手机 8 核（含 4 个能效核）全跑满会瞬间发热降频，推理越跑越卡。
        //    默认压到 ≤4，避免调度争抢与降频，换取长时间稳定速度。
        if (!env.containsKey("OLLAMA_NUM_THREADS")) {
            int def = Math.max(2, Math.min(Runtime.getRuntime().availableProcessors(), 4));
            env.put("OLLAMA_NUM_THREADS", String.valueOf(def));
            Log.i(TAG, "性能默认: OLLAMA_NUM_THREADS=" + def + "（限制线程数，防发热降频卡顿）");
        }
        // 2) KV 缓存 8bit 量化：显存/内存占用直接减半，长对话不再因为内存紧张而卡顿。
        if (!env.containsKey("OLLAMA_KV_CACHE_TYPE")) {
            env.put("OLLAMA_KV_CACHE_TYPE", "q8_0");
            Log.i(TAG, "性能默认: OLLAMA_KV_CACHE_TYPE=q8_0（KV 缓存 8bit 量化，内存减半）");
        }
        // 3) 内存 <8GB 时默认单路并行：ollama 自动并行会同时跑多个上下文，小内存直接被撑爆。
        if (!env.containsKey("OLLAMA_NUM_PARALLEL")) {
            long memMB = totalMemMB();
            if (memMB > 0 && memMB < 8 * 1024) {
                env.put("OLLAMA_NUM_PARALLEL", "1");
                Log.i(TAG, "性能默认: OLLAMA_NUM_PARALLEL=1（内存 " + memMB + "MB < 8GB，防多路并发撑爆内存）");
            }
        }
        return pb;
    }

    /** 读取 /proc/meminfo 的总内存（MB），失败返回 0。主界面推荐 GPU 层数时也会用到。 */
    static long totalMemMB() {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream("/proc/meminfo")));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("MemTotal:")) {
                        return Long.parseLong(line.replaceAll("[^0-9]", "").trim()) / 1024;
                    }
                }
            } finally {
                r.close();
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    /** 返回一个命令行描述，便于日志里显示实际启动了什么。 */
    public String describeCommand() {
        return SYSTEM_LINKER + " " + new File(libOllama, "ollama").getAbsolutePath() + " serve";
    }
}