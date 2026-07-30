"""
纯手工构建 APK（绕开 gradle）。
链路：aapt2 compile/link -> javac -> d8 -> 合成(classes.dex + assets/onnx + lib/arm64/.so) -> zipalign -> apksigner
无需 androidx：platform SDK + onnxruntime-android AAR + Kotlin 不需要（用 Java）。

需求（本机已具备）：
  - Android SDK build-tools 34（aapt2 / d8 / zipalign / apksigner），platform android-34
  - JDK 17
  - onnxruntime-android 1.18.0 AAR，解压出 classes.jar 和 jni/arm64-v8a/*.so
      默认从本机 gradle 缓存读取；也可用环境变量覆盖：
        ORT_AAR      = onnxruntime-android-1.18.0.aar 的完整路径
        ORT_EXTRACT  = 该 AAR 解压后的目录（含 classes.jar 与 jni/...）
      （解压方法：unzip onnxruntime-android-1.18.0.aar -d <ORT_EXTRACT>）

模型权重（约 199MB）不进 git，首次构建会自动从 HuggingFace 下载到 app/src/main/assets/。
"""
import os, sys, shutil, subprocess, zipfile, urllib.request

# ==== paths（全部相对本脚本，clone 到任何位置都能跑）====
ROOT = os.path.dirname(os.path.abspath(__file__))
APP_MAIN = os.path.join(ROOT, "app", "src", "main")
JAVA_DIR = os.path.join(APP_MAIN, "java")
RES_DIR = os.path.join(APP_MAIN, "res")
MANIFEST = os.path.join(APP_MAIN, "AndroidManifest.xml")
ASSETS_DIR = os.path.join(APP_MAIN, "assets")

SDK = os.environ.get("ANDROID_SDK_ROOT", os.environ.get("ANDROID_HOME", "D:/Android/Sdk"))
BUILD_TOOLS = os.path.join(SDK, "build-tools", "34.0.0")
ANDROID_JAR = os.path.join(SDK, "platforms", "android-34", "android.jar")
AAPT2 = os.path.join(BUILD_TOOLS, "aapt2.exe")
D8_BAT = os.path.join(BUILD_TOOLS, "d8.bat")
ZIPALIGN = os.path.join(BUILD_TOOLS, "zipalign.exe")
APKSIGNER = os.path.join(BUILD_TOOLS, "apksigner.bat")

JDK = os.environ.get("JAVA_HOME", "C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot")
JAVAC = os.path.join(JDK, "bin", "javac.exe")
KEYTOOL = os.path.join(JDK, "bin", "keytool.exe")
KEYSTORE = os.path.join(os.path.expanduser("~"), ".android", "debug.keystore")

ORT_AAR = os.environ.get("ORT_AAR",
    "C:/Users/y1690/.gradle/caches/modules-2/files-2.1/com.microsoft.onnxruntime/onnxruntime-android/1.18.0/25d96ccf86d0e0ef7f970b233041304bb64483eb/onnxruntime-android-1.18.0.aar")
ORT_EXTRACT = os.environ.get("ORT_EXTRACT", "D:/tmp/ort-aar-extract")

MODEL_URL = "https://huggingface.co/Carve/LaMa-ONNX/resolve/main/lama_fp32.onnx"
MODEL_NAME = "lama_fp32.onnx"

OUT = os.path.join(ROOT, "build_manual")
os.makedirs(OUT, exist_ok=True)
COMPILED = os.path.join(OUT, "compiled")
GEN = os.path.join(OUT, "gen")
CLASSES = os.path.join(OUT, "classes")
DEX = os.path.join(OUT, "dex")
APK_BASE = os.path.join(OUT, "app-res.apk")
APK_UNSIGNED = os.path.join(OUT, "app-unsigned.apk")
APK_ALIGNED = os.path.join(OUT, "app-aligned.apk")
APK_OUT = os.path.join(OUT, "app-debug.apk")

for d in [COMPILED, GEN, CLASSES, DEX]:
    os.makedirs(d, exist_ok=True)

def run(cmd, **kw):
    print("==> ", cmd)
    p = subprocess.run(cmd, shell=True, **kw)
    if p.returncode != 0:
        raise SystemExit(f"FAILED ({p.returncode}): {cmd}")

def ensure_model():
    os.makedirs(ASSETS_DIR, exist_ok=True)
    dst = os.path.join(ASSETS_DIR, MODEL_NAME)
    if os.path.exists(dst) and os.path.getsize(dst) > 1_000_000:
        print("model already present:", dst, round(os.path.getsize(dst)/1024/1024, 1), "MB")
        return
    print("downloading LaMa ONNX model (", MODEL_URL, ") ...")
    opener = urllib.request.build_opener()
    opener.addheaders = [("User-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")]
    urllib.request.install_opener(opener)
    urllib.request.urlretrieve(MODEL_URL, dst)
    print("downloaded:", dst, round(os.path.getsize(dst)/1024/1024, 1), "MB")

def ensure_ort():
    classes_jar = os.path.join(ORT_EXTRACT, "classes.jar")
    so_dir = os.path.join(ORT_EXTRACT, "jni", "arm64-v8a")
    if not os.path.exists(classes_jar):
        raise SystemExit(f"ORT classes.jar not found: {classes_jar}\n"
                          f"  -> unzip the AAR first, or set ORT_EXTRACT env var.")
    if not os.path.isdir(so_dir):
        raise SystemExit(f"ORT arm64 .so not found: {so_dir}\n"
                          f"  -> unzip the AAR first, or set ORT_EXTRACT env var.")
    return classes_jar, so_dir

# ==== 0) prepare deps ====
ensure_model()
ort_classes_jar, ort_so_dir = ensure_ort()

# ==== 1) aapt2 compile ====
print("\n=== [1] aapt2 compile ===")
for d, _, files in os.walk(RES_DIR):
    for f in files:
        full = os.path.join(d, f)
        rel_dir = os.path.relpath(d, RES_DIR).replace("\\", "/")
        out_dir = os.path.join(COMPILED, rel_dir).replace("\\", "/")
        os.makedirs(out_dir, exist_ok=True)
        run(f'"{AAPT2}" compile -o "{out_dir}" "{full}"')

# ==== 2) aapt2 link ====
print("\n=== [2] aapt2 link ===")
flats = []
for d, _, files in os.walk(COMPILED):
    for f in files:
        if f.endswith(".flat"):
            flats.append(os.path.join(d, f).replace("\\", "/"))
flats_arg = " ".join([f'"{x}"' for x in flats])
run(f'"{AAPT2}" link -I "{ANDROID_JAR}" '
    f'--auto-add-overlay --java "{GEN}" '
    f'-o "{APK_BASE}" --manifest "{MANIFEST}" '
    f'{flats_arg}')

# ==== 3) javac compile ====
print("\n=== [3] javac compile ===")
java_files = []
for d, _, files in os.walk(JAVA_DIR):
    for f in files:
        if f.endswith(".java"):
            java_files.append(os.path.join(d, f))
r_java = []
for d, _, files in os.walk(GEN):
    for f in files:
        if f.endswith(".java"):
            r_java.append(os.path.join(d, f))
classpath = f'"{ANDROID_JAR}";{ort_classes_jar}'
run(f'"{JAVAC}" -encoding UTF-8 -d "{CLASSES}" -classpath {classpath} '
    f'{" ".join([f"{x}" for x in java_files + r_java])}')

# ==== 4) d8 dex ====
print("\n=== [4] d8 (pack classes into jar first) ===")
classes_jar = os.path.join(OUT, "classes.jar")
with zipfile.ZipFile(classes_jar, "w", zipfile.ZIP_DEFLATED) as zf:
    for d, _, files in os.walk(CLASSES):
        for f in files:
            if f.endswith(".class"):
                full = os.path.join(d, f)
                rel = os.path.relpath(full, CLASSES).replace("\\", "/")
                zf.write(full, rel)
run(f'"{D8_BAT}" --min-api 24 --lib "{ANDROID_JAR}" --output "{DEX}" '
    f'"{classes_jar}" "{ort_classes_jar}"')

# ==== 5) 合成 APK ====
print("\n=== [5] combine apk ===")
shutil.copyfile(APK_BASE, APK_UNSIGNED)
with zipfile.ZipFile(APK_UNSIGNED, "a", zipfile.ZIP_DEFLATED) as zf:
    zf.write(os.path.join(DEX, "classes.dex"), "classes.dex")
    # assets
    for f in os.listdir(ASSETS_DIR):
        zf.write(os.path.join(ASSETS_DIR, f), "assets/" + f)
    # .so
    if os.path.isdir(ort_so_dir):
        for f in os.listdir(ort_so_dir):
            zf.write(os.path.join(ort_so_dir, f), "lib/arm64-v8a/" + f)

# ==== 6) zipalign ====
print("\n=== [6] zipalign ===")
run(f'"{ZIPALIGN}" -f -p 4 "{APK_UNSIGNED}" "{APK_ALIGNED}"')

# ==== 7) apksigner ====
print("\n=== [7] apksigner ===")
if not os.path.exists(KEYSTORE):
    print("debug keystore missing, creating one...")
    run(f'"{KEYTOOL}" -genkeypair -keystore "{KEYSTORE}" -storepass android -keypass android '
        f'-alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" '
        f'-keyalg RSA -keysize 2048 -validity 10000')
run(f'"{APKSIGNER}" sign --ks "{KEYSTORE}" --ks-pass pass:android '
    f'--key-pass pass:android --ks-key-alias androiddebugkey '
    f'--out "{APK_OUT}" "{APK_ALIGNED}"')

print("\n=== APK BUILT ===")
print(APK_OUT, "size=", round(os.path.getsize(APK_OUT)/1024/1024, 1), "MB")
