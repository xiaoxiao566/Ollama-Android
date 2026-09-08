# Ollama-Android

Android 端本地大模型服务：内置 ollama 引擎（免 ROOT 解压运行），支持 Vulkan GPU 加速，液态玻璃 UI，内置聊天界面与全部数字参数调节。

> 适用于 arm64-v8a 设备，建议 Android 12+（Vulkan 加速需要系统 Vulkan 驱动，Android 13+ 体验最佳）。

## 功能

- **免 ROOT 内置引擎**：首次启动自动解压 ollama / llama.cpp 引擎到应用私有目录，通过系统 linker 在 SELinux 限制下运行
- **Vulkan GPU 加速**：内置 `libggml-vulkan.so`，自动发现系统 Vulkan 驱动，显著提升推理速度（无 Vulkan 驱动时自动回退 CPU）
- **液态玻璃 UI**：毛玻璃模糊 + 左上受光内高光，按钮/卡片/聊天气泡全套玻璃质感
- **聊天界面**：流式输出，思考过程（reasoning）以粗体显示在气泡顶部
- **参数全调节**：设置页「变量 / 高级」两级分区，覆盖 ollama 全部数字参数：
  - 服务端环境变量：`OLLAMA_NUM_PARALLEL` `OLLAMA_MAX_LOADED_MODELS` `OLLAMA_MAX_QUEUE` `OLLAMA_CONTEXT_LENGTH` `OLLAMA_MAX_TRANSFER_STREAMS` `OLLAMA_PORT` `OLLAMA_NUM_THREADS`
  - 对话采样参数：`num_ctx` `num_predict` `seed` `top_k` `repeat_last_n` `mirostat` `num_gpu` `num_thread` `num_batch` `num_gqa` `temperature` `top_p` `repeat_penalty` `mirostat_tau` `mirostat_eta` `tfs_z`
  - 留空 = 使用 ollama 默认值；「高级」分区点开前有警告提示
- **日志落盘**：有存储权限时日志写入 `/storage/emulated/0/ollama-log/`，文件名 `0x_服务启动时间(全数字).log`；崩溃堆栈写入 `crash_时间.log`

## 安装

从 [Releases](https://github.com/xiaoxiao566/Ollama-Android/releases) 下载对应版本 APK 直接安装（debug 签名，各版本可覆盖升级）。

## 构建

```bash
# 依赖：Android SDK build-tools（aapt2 / javac / d8 / zipalign / apksigner）+ JDK 17
# 首次构建前把 SDK 路径填入 build.sh
bash build.sh
```

产物输出到 `build/Ollama-arm64.apk`。

## 设置说明

| 分区 | 参数 |
|---|---|
| 变量（基础，可放心调） | `OLLAMA_NUM_PARALLEL`、`OLLAMA_MAX_LOADED_MODELS`、`OLLAMA_MAX_QUEUE`、`OLLAMA_PORT`、`OLLAMA_NUM_THREADS`、`num_ctx`、`num_predict`、`num_gpu`、`num_thread`、`temperature`、`top_p` |
| 高级（可能影响模型运行） | `OLLAMA_CONTEXT_LENGTH`、`OLLAMA_MAX_TRANSFER_STREAMS`、`seed`、`top_k`、`repeat_last_n`、`mirostat`、`num_batch`、`num_gqa`、`repeat_penalty`、`mirostat_tau`、`mirostat_eta`、`tfs_z` |

## 致谢

- [ollama](https://github.com/ollama/ollama) - 本地大模型运行时
- [AndroidLiquidGlass (Kyant0/backdrop)](https://github.com/Kyant0/AndroidLiquidGlass) - 液态玻璃效果
