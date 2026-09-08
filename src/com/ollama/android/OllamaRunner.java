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
    private static final String TERMUX_VERSION = "0.118.0";
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
        Log.i(TAG, "资源就绪" + (hasVulkanBackend() ? "（含 Vulkan 后端）" : "（仅 CPU 后端）"));
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
        boolean vulkan = Prefs.GPU_VULKAN.equals(gpu);

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
        // GPU 后端选择
        env.put("OLLAMA_VULKAN", vulkan ? "1" : "0");
        if (Prefs.GPU_OPENCL.equals(gpu)) {
            // 现代 llama.cpp 已移除 OpenCL 后端；这里仅作标识，实际回退 CPU
            env.put("OLLAMA_OPENCL", "1");
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
        return pb;
    }

    /** 返回一个命令行描述，便于日志里显示实际启动了什么。 */
    public String describeCommand() {
        return SYSTEM_LINKER + " " + new File(libOllama, "ollama").getAbsolutePath() + " serve";
    }
}