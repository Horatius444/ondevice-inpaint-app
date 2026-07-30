import os, numpy as np, onnxruntime as ort
from PIL import Image, ImageDraw, ImageFont
import urllib.request

ONNX = "D:/tmp/migan_pipeline_v2.onnx"
os.makedirs("D:/tmp", exist_ok=True)
W, H = 640, 480

# 1) 测试原图：优先真实照片，失败则生成带结构的图
raw = "D:/tmp/test_raw.jpg"
img = None
try:
    urllib.request.urlretrieve("https://picsum.photos/640/480", raw)
    img = Image.open(raw).convert("RGB").resize((W, H))
    print("use real photo")
except Exception as e:
    print("picsum failed, fallback:", e)
    img = Image.new("RGB", (W, H), (210, 215, 220))
    d = ImageDraw.Draw(img)
    for i in range(0, H, 48):
        for j in range(0, W, 48):
            d.rectangle([j, i, j + 44, i + 44],
                        fill=((j * 7) % 255, (i * 11) % 255, (j + i) % 255))

# 2) 叠水印 + 生成 mask（水印区域=0，其余=255）
mask = Image.new("L", (W, H), 255)
dimg = img.copy()
dd = ImageDraw.Draw(dimg)
dmask = ImageDraw.Draw(mask)
try:
    fnt = ImageFont.truetype("C:/Windows/Fonts/arial.ttf", 52)
except Exception:
    fnt = ImageFont.load_default()
txt = "SAMPLE WATERMARK"
bbox = dd.textbbox((0, 0), txt, font=fnt)
tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
x, y = (W - tw) // 2, (H - th) // 2 - 40
dd.text((x, y), txt, fill=(255, 255, 255), font=fnt)
dmask.rectangle([x - 6, y - 6, x + tw + 6, y + th + 6], fill=0)
# 左上角一个 logo 方块水印
dd.rectangle([30, 30, 150, 120], fill=(235, 30, 30))
dmask.rectangle([30, 30, 150, 120], fill=0)

# 3) 转 NCHW uint8
img_arr = np.asarray(dimg).astype(np.uint8)
mask_arr = np.asarray(mask).astype(np.uint8)
img_nchw = np.transpose(img_arr, [2, 0, 1])[None, ...]
mask_nchw = mask_arr[None, None, ...]

# 4) 推理
sess = ort.InferenceSession(ONNX, providers=["CPUExecutionProvider"])
res = sess.run(["result"], {"image": img_nchw, "mask": mask_nchw})[0][0]
res = np.transpose(res, [1, 2, 0]).astype(np.uint8)
out = Image.fromarray(res, "RGB")

# 5) 保存结果 + 对比图
out.save("D:/tmp/test_result.png")
comp = Image.new("RGB", (W * 3, H))
comp.paste(dimg, (0, 0))
comp.paste(mask.convert("RGB"), (W, 0))
comp.paste(out, (W * 2, 0))
comp.save("D:/tmp/test_compare.png")
print("OK -> D:/tmp/test_result.png  D:/tmp/test_compare.png")
print("result size:", out.size, "src size:", img.size)
