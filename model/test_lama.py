"""
LaMa 真实效果测试：
1. 加载真实照片
2. 在照片右下角叠加真实水印（半透明白字 + 阴影），模拟 IG/抖音风格
3. 用 LaMa 去除
4. 输出对比图：原图 / 加水印 / 遮罩 / 修复结果

Pipeline (镜像 simple_lama.py):
  找到 mask bbox → 加 padding crop → resize 到 512x512 → LaMa → resize 回原 crop 尺寸 → paste 回去
"""
import os, sys, cv2, numpy as np, onnxruntime as ort, urllib.request

MODEL = r"C:/Users/y1690/WorkBuddy/2026-07-29-17-10-59/ondevice-inpaint-app/model/lama_fp32.onnx"
TEST_IMG = r"D:/tmp/test_real.jpg"
OUT = r"D:/tmp/lama_test.png"
os.makedirs(r"D:/tmp", exist_ok=True)

assert os.path.exists(MODEL), f"model not found: {MODEL}"
assert os.path.exists(TEST_IMG), f"img not found: {TEST_IMG}"

sess = ort.InferenceSession(MODEL, providers=['CPUExecutionProvider'])
print("LaMa loaded. inputs:", [i.name for i in sess.get_inputs()], "outputs:", [o.name for o in sess.get_outputs()])

def overlay_watermark(img, text="© WATERTEST 2026"):
    """在右下角画半透明白字 + 阴影 + 矩形底"""
    h, w = img.shape[:2]
    out = img.copy()
    font = cv2.FONT_HERSHEY_SIMPLEX
    scale = max(1.0, w / 500.0)
    thick = max(2, int(w / 350))
    (tw, th), baseline = cv2.getTextSize(text, font, scale, thick)
    pad = 18
    x2 = w - 24; y2 = h - 36
    x1 = x2 - tw - pad*2; y1 = y2 - th - pad*2
    # 半透明白底
    overlay = out.copy()
    cv2.rectangle(overlay, (x1, y1), (x2, y2), (255, 255, 255), -1)
    cv2.addWeighted(overlay, 0.55, out, 0.45, 0, out)
    # 阴影
    cv2.putText(out, text, (x2 - tw + 2, y2 - pad + 2), font, scale, (0,0,0), thick + 1, cv2.LINE_AA)
    cv2.putText(out, text, (x2 - tw, y2 - pad), font, scale, (255,255,255), thick, cv2.LINE_AA)
    # mask 区域
    mask_box = (x1, y1, x2, y2)
    return out, mask_box

def build_mask_like_draw(shape, box, pad_extra=0):
    """返回 0/255 mask, 255=涂掉区域"""
    h, w = shape[:2]
    mask = np.zeros((h, w), np.uint8)
    x1, y1, x2, y2 = box
    x1 = max(0, x1 - pad_extra); y1 = max(0, y1 - pad_extra)
    x2 = min(w, x2 + pad_extra); y2 = min(h, y2 + pad_extra)
    mask[y1:y2, x1:x2] = 255
    return mask

def lama_inpaint(img, mask):
    """simple_lama 风格 crop → 512 → LaMa → paste"""
    if mask.max() == 0:
        return img
    rows, cols = np.where(mask > 0)
    y1, y2 = rows.min(), rows.max()
    x1, x2 = cols.min(), cols.max()
    pad = 200
    y1 = max(0, y1 - pad); y2 = min(img.shape[0], y2 + pad)
    x1 = max(0, x1 - pad); x2 = min(img.shape[1], x2 + pad)

    crop_img = img[y1:y2, x1:x2]
    crop_mask = mask[y1:y2, x1:x2]
    crop_h, crop_w = crop_img.shape[:2]

    target = (512, 512)
    img_r = cv2.resize(crop_img, target, interpolation=cv2.INTER_AREA)
    mask_r = cv2.resize(crop_mask, target, interpolation=cv2.INTER_NEAREST)

    img_in = (img_r.astype(np.float32) / 255.0).transpose(2, 0, 1)[None]   # [1,3,512,512]
    msk_in = ((mask_r > 0).astype(np.float32))[None, None]                 # [1,1,512,512], 1=erase

    out = sess.run(None, {'image': img_in, 'mask': msk_in})[0][0]          # [3,512,512] float32
    # sapienkit 文档：output 已在 [0,255] float32
    out = np.clip(out, 0, 255).transpose(1, 2, 0).astype(np.uint8)         # [512,512,3] uint8

    out_full = cv2.resize(out, (crop_w, crop_h), interpolation=cv2.INTER_CUBIC)
    result = img.copy()
    # 边缘羽化 1px 让拼接更自然
    mask_smooth = cv2.GaussianBlur((crop_mask > 0).astype(np.float32), (5,5), 0)[..., None]
    blend = (out_full.astype(np.float32) * mask_smooth +
             crop_img.astype(np.float32) * (1 - mask_smooth)).astype(np.uint8)
    result[y1:y2, x1:x2] = blend
    return result

# ---- run ----
img = cv2.imread(TEST_IMG)  # BGR
print("img shape:", img.shape)
img_rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
wmed, box = overlay_watermark(img_rgb)
mask = build_mask_like_draw(img_rgb.shape, box, pad_extra=8)
print("mask area px:", int((mask>0).sum()))

print("running LaMa inference...")
import time; t0=time.time()
fixed = lama_inpaint(wmed, mask)
print(f"inpaint done in {time.time()-t0:.2f}s")

# 拼对比图: 原图 | 加水印 | 遮罩 | 修复
h, w = img_rgb.shape[:2]
mask_vis = np.repeat(mask[..., None], 3, axis=2)
mask_vis[mask > 0] = [255, 0, 0]
canvas = np.zeros((h, 4*w + 3*10, 3), np.uint8)
canvas[:, :w] = img_rgb
canvas[:, w+10:2*w+10] = wmed
canvas[:, 2*w+20:3*w+20] = mask_vis
canvas[:, 3*w+30:4*w+30] = fixed
# 顶标注
for i, t in enumerate(["ORIGINAL", "WITH WATERMARK", "MASK (red=erase)", "LAMA RESULT"]):
    cv2.putText(canvas, t, (i*(w+10) + 12, 32), cv2.FONT_HERSHEY_SIMPLEX, 0.9, (255,255,255), 2, cv2.LINE_AA)
    cv2.putText(canvas, t, (i*(w+10) + 12, 32), cv2.FONT_HERSHEY_SIMPLEX, 0.9, (0,0,0), 1, cv2.LINE_AA)

cv2.imwrite(OUT, cv2.cvtColor(canvas, cv2.COLOR_RGB2BGR))
print("saved:", OUT)
