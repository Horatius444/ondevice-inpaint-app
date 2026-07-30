# Inpaint Pro — 端侧 AI 去水印 / 涂抹区域修复（Android）

纯端侧、不联网、零云依赖的 Android 图像修复 App。用手指涂抹想消除的水印 / logo / 路人 / 涂抹痕迹，
本地运行 **LaMa（Big LaMa, WACV 2022）** 修复模型，逐像素补全背景。

- 模型推理：**ONNX Runtime Mobile 1.18.0**（`arm64-v8a`），直接吃官方 ONNX，零转换
- 模型：**LaMa (big-lama)** — 端侧 / 服务器去水印事实标准（傅里叶卷积，擅长结构化内容补全）
- 最低系统：**Android 7.0 (API 24)**，目标 **API 34**
- 构建方式：手工构建链（aapt2 → javac → d8 → zipalign → apksigner），**绕开 gradle**

## 功能

| 能力 | 说明 |
|---|---|
| 选图 | 从相册 / 文件选一张图 |
| 涂抹标记 | 手指在画布上涂要消除的区域（白色半透叠加）；笔刷 3 档：64 / 100 / 150 px |
| 修复 | 跑 LaMa：自动裁剪涂抹包围盒（外扩 200px 取上下文）→ resize 512×512 → 推理 → 羽化混合嵌回原图 |
| 拖拽对比 | 修复后进入对比模式，**拖动中间圆手柄**左右扫，实时对比原图 vs 修复结果 |
| 回退 / 向前 | 按笔迹逐步撤销 / 重做涂抹（↶ / ↷ 两个独立图标） |
| 清空 | 一键重置所有修复效果，回到原始图片 |
| 保存 | 结果存入系统相册 `Pictures/OnDeviceInpaint/` |

## 目录结构

```
ondevice-inpaint-app/
├── app/src/main/
│   ├── AndroidManifest.xml        # package=com.example.inpaint, 无 androidx
│   ├── assets/                    # lama_fp32.onnx（构建时自动下载，不进 git）
│   ├── java/com/example/inpaint/
│   │   ├── MainActivity.java      # 模型加载 + 涂抹 + 推理 + 保存
│   │   ├── CompareView.java       # 双模画布（涂抹 / 拖拽对比）+ 笔迹历史
│   │   └── MediaStoreSaver.java   # 保存到系统相册
│   └── res/                       # 布局 / 颜色 / 字符串 / 矢量图标(drawable)
├── build_manual.py               # 手工构建脚本（见下）
└── docs/                         # 效果截图
```

## 构建

### 前置条件（本机已具备）
- Android SDK，含 `build-tools/34.0.0`（aapt2 / d8 / zipalign / apksigner）与 `platforms/android-34`
- JDK 17
- `onnxruntime-android 1.18.0` 的 AAR，解压出 `classes.jar` 与 `jni/arm64-v8a/*.so`
  - 默认从本机 gradle 缓存读取；也可用环境变量覆盖：
    - `ORT_AAR`：AAR 完整路径
    - `ORT_EXTRACT`：AAR 解压目录（含 `classes.jar` 与 `jni/`）
  - 解压方法：`unzip onnxruntime-android-1.18.0.aar -d $ORT_EXTRACT`
- Android SDK 路径可用 `ANDROID_SDK_ROOT` / `ANDROID_HOME` 指定（默认 `D:/Android/Sdk`）

### 步骤
```bash
python build_manual.py
```
脚本会：
1. 若 `app/src/main/assets/lama_fp32.onnx` 缺失，自动从 HuggingFace 下载（约 199MB）
2. aapt2 编译/链接资源 → javac 编译 Java → d8 打 dex → 合成 assets + .so → zipalign → apksigner 签名
3. 产物：`build_manual/app-debug.apk`

> 注：模型权重（199MB）超过 GitHub 单文件 100MB 上限，不纳入版本库。重新构建时脚本会自动拉取。

## 安装 & 测试

```bash
adb install -r build_manual/app-debug.apk
adb shell am start -n com.example.inpaint/.MainActivity
```
1. 点「选图」选一张带水印的照片
2. 手指涂抹水印区域（白色半透叠加）
3. 点「修复」→ 首次会先把模型从 assets 拷到私有目录（约 1–2 秒），之后秒级出结果
4. **拖动中间圆手柄**对比原图 / 修复结果
5. 满意后点「保存」存入相册

## 已知限制 & 改进方向

- **速度**：当前 ORT CPU 推理，移动端约 1–3s（取决于涂抹区域 / 机型）。可换 NCNN + INT8 量化提速。
- **精度**：LaMa 对大块、连续水印最好；极细字体 / 复杂纹理可能留轻微残影。可换 MAT / ZITS++ 权重。
- **盲去水印**：当前需手动涂抹（方法 1）。全自动不涂抹的去水印是另一条路线（WDNet / MorphoMod），后续可加「自动模式」开关。
- **样式**：未引入 androidx（受构建环境限制），使用平台 `Theme.DeviceDefault`；视觉偏原生安卓。
