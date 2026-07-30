import os, urllib.request, onnxruntime as ort

url = "https://huggingface.co/andraniksargsyan/migan/resolve/main/migan_pipeline_v2.onnx"
dst = "D:/tmp/migan_pipeline_v2.onnx"
os.makedirs(os.path.dirname(dst), exist_ok=True)

if not os.path.exists(dst) or os.path.getsize(dst) < 1_000_000:
    print("downloading from HF ...")
    opener = urllib.request.build_opener()
    opener.addheaders = [("User-Agent", "Mozilla/5.0")]
    urllib.request.install_opener(opener)
    urllib.request.urlretrieve(url, dst)
    print("downloaded bytes:", os.path.getsize(dst))
else:
    print("already exists:", os.path.getsize(dst))

print("=== ONNX IO ===")
sess = ort.InferenceSession(dst, providers=["CPUExecutionProvider"])
for i in sess.get_inputs():
    print("INPUT ", i.name, "| shape", i.shape, "| type", i.type)
for o in sess.get_outputs():
    print("OUTPUT", o.name, "| shape", o.shape, "| type", o.type)
