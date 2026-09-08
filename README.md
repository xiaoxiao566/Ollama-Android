# Ollama-Android

Android 端本地大模型服务：内置 ollama 引擎（免 ROOT 解压运行），默认开启 Vulkan GPU 加速，液态玻璃 UI，内置聊天界面与全部数字参数调节。

> 适用于 arm64-v8a 设备，建议 Android 12+（Vulkan 加速需要系统 Vulkan 驱动，Android 13+ 体验最佳）。

## 功能

- **免 ROOT 内置引擎**：首次启动自动解压 ollama / llama.cpp 引擎到应用私有目录，通过系统 linker 在 SELinux 限制下运行
- **Vulkan GPU 加速（可选）**：内置 `libggml-vulkan.so`，通过系统 Vulkan 加载器访问 `/vendor/lib64/hw/` 下的真实 GPU 驱动；默认 CPU 后端（最稳定），需要加速时到设置页「GPU 后端」选择 Vulkan，无驱动的设备会自动回退 CPU，运行日志用 `[GPU]` 标注实际生效的后端
- **液态玻璃 UI**：毛玻璃 + 左上受光内高光，按钮/卡片/聊天气泡全套玻璃质感
- **聊天界面**：气泡式对话，思考过程（reasoning）以粗体显示在气泡顶部，可选显示推理速度（tokens/s）
- **日志面板自动滚动**：服务输出新日志时自动滚动到底部；日志同时落盘到 `/storage/emulated/0/ollama-log/`，文件名 `0x_服务启动时间(全数字).log`
- **参数全调节**：设置页「变量 / 高级」两级分区，覆盖 ollama 全部数字参数：
  - 服务端环境变量：`OLLAMA_NUM_PARALLEL` `OLLAMA_MAX_LOADED_MODELS` `OLLAMA_MAX_QUEUE` `OLLAMA_CONTEXT_LENGTH` `OLLAMA_MAX_TRANSFER_STREAMS` `OLLAMA_PORT` `OLLAMA_NUM_THREADS`
  - 对话采样参数：`num_ctx` `num_predict` `seed` `top_k` `repeat_last_n` `mirostat` `num_gpu` `num_thread` `num_batch` `num_gqa` `temperature` `top_p` `repeat_penalty` `mirostat_tau` `mirostat_eta` `tfs_z`
  - 模型运行选项：`--keepalive` `--verbose` `--nowordwrap` `--insecure` `--think` `--hidethinking` `--experimental` `--experimental-websearch` `--system`
  - 留空 = 使用 ollama 默认值；「高级」分区点开前有警告提示

## 安装

从 [Releases](https://github.com/xiaoxiao566/Ollama-Android/releases) 下载对应版本 APK 直接安装（debug 签名，各版本可覆盖升级）。

## 常见问题

**怎么确认 Vulkan 加速真的生效了？**

启动服务后看上方的运行日志：出现 `[GPU] 已启用 Vulkan 加速（Vulkan0: ...）` 表示走 GPU；出现 `[GPU] 未发现可用 Vulkan 设备，已回退 CPU` 表示当前设备没有可用的 Vulkan 驱动（这种情况在 Termux 里同样无法用 GPU）。

**为什么比 Termux 慢？**

大概率是回退到了 CPU。确认方式同上；如果日志显示已启用 Vulkan 但仍然偏慢，可以到设置里调大 `num_ctx`、`num_gpu`（-1 自动分配）或尝试调整 `OLLAMA_NUM_PARALLEL`，并用 `--verbose` 查看实际 tokens/s。

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

## 更新日志

见 [CHANGELOG.md](CHANGELOG.md)。

## 致谢

- [ollama](https://github.com/ollama/ollama) - 本地大模型运行时
- [AndroidLiquidGlass (Kyant0/backdrop)](https://github.com/Kyant0/AndroidLiquidGlass) - 液态玻璃效果
