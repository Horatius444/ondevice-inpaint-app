"""
对比 LaMa fp32 vs uint8 量化效果（同一张图、同一 mask）
输出：双联对比 + PSNR
"""
import os, cv2, numpy as np, onnxruntime as ort

MODEL_FP32 = r"C:/Users/y1690/WorkBuddy/2026-07-29-17-10-59/ondevice-inpaint-app/model/lama_fp32.onnx"
MODEL_UINT8 = r"C:/Users/y1690/WorkBuddy/2026-07-29-17-10-59/ondevice-inpaint-app/model/lama_uint8.onnx"
TEST_IMG = r"D:/tmp/test_real.jpg"
OUT = r"D:/tmp/lama_compare.png"

img = cv2.cvtColor(cv2.imread(TEST_IMG), cv2.COLOR_BGR2RGB)
h, w = img.shape[:2]

# 同样的水印
font = cv2.FONT_HERSHEY_SIMPLEX
scale = max(1.0, w / 500.0)
thick = max(2, int(w / 350))
text = "© WATERTEST 2026"
(tw, th), baseline = cv2.getTextSize(text, font, scale, thick)
pad = 18
x2 = w - 24; y2 = h - 36
x1 = x2 - tw - pad*2; y1 = y2 - th - pad*2
wmed = img.copy()
ov = wmed.copy()
cv2.rectangle(ov, (x1, y1), (x2, y2), (255, 255, 255), -1)
cv2.addWeighted(ov, 0.55, wmed, 0.45, 0, wmed)
cv2.putText(wmed, text, (x2 - tw + 2, y2 - pad + 2), font, scale, (0,0,0), thick + 1, cv2.LINE_AA)
cv2.putText(wmed, text, (x2 - tw, y2 - pad), font, scale, (255,255,255), thick, cv2.LINE_AA)

mask = np.zeros((h, w), np.uint8)
mask[y1-8:y2+8, x1-8:x2+8] = 255

def inpaint(model_path, img_in, mask_in):
    sess = ort.InferenceSession(model_path, providers=['CPUExecutionProvider'])
    rows, cols = np.where(mask_in > 0)
    y1b, y2b = rows.min(), rows.max()
    x1b, x2b = cols.min(), cols.max()
    pad_b = 200
    y1b = max(0, y1b - pad_b); y2b = min(img_in.shape[0], y2b + pad_b)
    x1b = max(0, x1b - pad_b); x2b = min(img_in.shape[1], x2b + pad_b)
    crop_img = img_in[y1b:y2b, x1b:x2b]
    crop_mask = mask_in[y1b:y2b, x1b:x2b]
    ch, cw = crop_img.shape[:2]
    img_r = cv2.resize(crop_img, (512, 512), interpolation=cv2.INTER_AREA)
    msk_r = cv2.resize(crop_mask, (512, 512), interpolation=cv2.INTER_NEAREST)
    img_in2 = (img_r.astype(np.float32) / 255.0).transpose(2, 0, 1)[None]
    msk_in2 = ((msk_r > 0).astype(np.float32))[None, None]
    out = sess.run(None, {'image': img_in2, 'mask': msk_in2})[0][0]
    out = np.clip(out, 0, 255).transpose(1, 2, 0).astype(np.uint8)
    out_full = cv2.resize(out, (cw, ch), interpolation=cv2.INTER_CUBIC)
    result = img_in.copy()
    mask_smooth = cv2.GaussianBlur((crop_mask > 0).astype(np.float32), (5,5), 0)[..., None]
    blend = (out_full.astype(np.float32) * mask_smooth + crop_img.astype(np.float32) * (1 - mask_smooth)).astype(np.uint8)
    result[y1b:y2b, x1b:x2b] = blend
    return result

import time
print("running fp32...")
t=time.time(); r32 = inpaint(MODEL_FP32, wmed, mask); print(f"  fp32: {time.time()-t:.2f}s")
print("running uint8...")
t=time.time(); r8 = inpaint(MODEL_UINT8, wmed, mask); print(f"  uint8: {time.time()-t:.2f}s")

# 放大查看水印区
y1c, y2c, x1c, x2c = max(0,y1-30), min(h,y2+30), max(0,x1-30), min(w,x2+30)
crop_orig = img[y1c:y2c, x1c:x2c]
crop_wm = wmed[y1c:y2c, x1c:x2c]
crop_32 = r32[y1c:y2c, x1c:x2c]
crop_8 = r8[y1c:y2c, x1c:x2c]

# PSNR in the masked area
def psnr(a, b, m):
    mm = m > 0
    if mm.sum() == 0: return 0
    aa = a[mm].astype(np.float64); bb = b[mm].astype(np.float64)
    mse = ((aa-bb)**2).mean()
    if mse == 0: return 99
    return 10*np.log10(255*255/mse)

m_crop = mask[y1c:y2c, x1c:x2c]
print(f"PSNR fp32 vs uint8 in masked area: {psnr(crop_32, crop_8, m_crop):.2f} dB")

# 拼 4 列: 原 / 加水印 / fp32 / uint8
panel = np.zeros((crop_orig.shape[0], 4*crop_orig.shape[1] + 3*12, 3), np.uint8)
for i, (im, t) in enumerate([(crop_orig, "ORIGINAL"), (crop_wm, "WITH WATERMARK"), (crop_32, "LAMA FP32 (198MB)"), (crop_8, "LAMA UINT8 (59MB)")]):
    panel[:, i*(crop_orig.shape[1]+12):i*(crop_orig.shape[1]+12)+crop_orig.shape[1]] = im
    cv2.putText(panel, t, (i*(crop_orig.shape[1]+12) + 8, 26), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (255,255,255), 2, cv2.LINE_AA)
    cv2.putText(panel, t, (i*(crop_orig.shape[1]+12) + 8, 26), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0,0,0), 1, cv2.LINE_AA)

cv2.imwrite(OUT, cv2.cvtColor(panel, cv2.COLOR_RGB2BGR))
print("saved:", OUT)
