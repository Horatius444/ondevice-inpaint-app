# Flutter 多端移植蓝图（LaMa 端侧修复）

> 目标：用 Flutter 单代码库重写现有原生 Android 版，复用 `lama_fp32.onnx`（动态输入 512/384/256）。
> Android 本机出 APK 装真机验证等效；iOS 写成 Mac-ready 代码，等用户有 Mac 再出包。

## 1. 工程结构（flutter create 生成后填充）
```
lib/
  main.dart            # 入口 + 权限/选图路由
  inference/
    model_loader.dart  # assets→文件目录复制 + model_version 缓存失效哨兵
    inpaint.dart       # 1:1 移植 MainActivity.runRepair 算法（纯函数，易测）
  ui/
    editor_page.dart   # HUD 主界面（极简）
    compare_view.dart  # CustomPainter + GestureDetector 拖动中线对比 + 笔刷涂抹
    mask_painter.dart  # 涂抹历史 / 撤销重做 / 清空重置
pubspec.yaml           # onnxruntime + 权限依赖；assets 指向模型
android/ ios/          # 平台宿主（iOS-ready，禁止 Android-only 硬编码）
assets/lama_fp32.onnx  # 复用现有模型（.gitignore 排除，构建时下载/拷贝）
```

## 2. 依赖
- `onnxruntime`（dart:ffi 封装 ORT C API）：明确支持 Android + iOS，**CPU EP 跑 Einsum/FFT**（LaMa 关键算子）。推理 API 与现有 Java 套几乎 1:1。
- `path_provider`：跨平台文件目录（替代 `getFilesDir()`）。
- 保存 PNG：`image_gallery_saver` 或 `gal`（Android 写相册 / iOS 写相册）。
- 权限：`permission_handler` 分平台申请（READ_MEDIA_IMAGES / 相册写）。

## 3. Dart 推理核心（inpaint.dart，对照 MainActivity.java 1:1）
- **加载**：`rootBundle.load(assets/lama_fp32.onnx)` → 写入 `path_provider` 目录；用 `model_version` 哨兵文件对比 assets 内版本，不一致则重新拷贝（解决旧版缓存 bug）。后台线程加载，UI 显示"首次加载模型约 200MB"。
- **Session**：`OrtSessionOptions` → `setIntraOpNumThreads(可用核数)` + XNNPACK EP（插件对应 API）。
- **runRepair 算法**（纯函数，输入 `Image` base + 同尺寸 mask 数组）：
  1. 统计 mask 像素（>10 视为涂抹）→ bounding box → 扩张 `PAD=200` 得裁剪窗 `(x1,y1,x2,y2)`。
  2. 裁剪 crop → `createScaledBitmap` 到 `modelInputSize`(512/384/256)。
  3. 构造 image `float[1,3,H,W]∈[0,1]`、mask `float[1,1,H,W]∈{0,1}`。
  4. `session.run({"image","mask"})` → out `float[3,H,W]`。
  5. 还原 ARGB → 放大回 cropW×cropH。
  6. mask 放大 + `boxBlur(radius=5)` 平滑 → alpha 融合 `out*ms + crop*(1-ms)`。
  7. 写回原分辨率 result（未涂抹像素 100% 原样保留，整图分辨率不变）。
- **画质三档**：512（最清晰 ~7-8s）/ 384（均衡 ~4s，默认）/ 256（更快 ~2s，质量略降）。语义建议改名"修复精度"。

## 4. UI 交互（保持已认可方案）
- 选图 / 一键修复（显示进度条）/ 保存原分辨率 PNG
- 中间拖动线对比 原图 ↔ 修复图
- 笔刷三档 64/100/150（单按钮循环，原"最大作为最小"）
- 撤销 / 重做 两个独立图标（控制涂抹区域历史）
- 清空 = 重置所有涂抹
- 画质三档切换（建议改名"修复精度"）

## 5. iOS-ready 要点
- 资源/文件只用 `path_provider` 跨平台 API，禁止硬编码 `/sdcard` 等路径。
- 权限统一走 `permission_handler`，分平台申请。
- ORT 在 iOS 走 CPU EP；CoreML EP 对 Einsum 无加速，加分有限，可选。
- 不引入任何 Android-only 常量 / 原生通道。

## 6. 风险
- Flutter 版本 vs `onnxruntime` 插件 SDK 约束（升级到 latest stable 应解决；若仍冲突则 pin 兼容旧版插件）。
- `flutter build apk` 仍需 Gradle：本机已缓存 gradle 8.5/8.9/8.13 + 代理通，**预期可过，必须实证**（先出模板 APK）。
- iOS 编译/真机/上架必须在 Mac，本机无法验证。

## 7. 进度
- [x] 项目迁移至 `D:\Android_personal_workspace\ondevice-inpaint-app`（C 盘源已删，159 文件校验一致）
- [x] 配置 git 代理 `http://127.0.0.1:7897`（解决 flutter upgrade 卡死）
- [ ] Flutter 升级完成（后台 running）
- [ ] D 盘建 Flutter 工程 + 模板 APK 实证
- [ ] Dart 推理核心 + UI 重实现
- [ ] 真机验证等效
