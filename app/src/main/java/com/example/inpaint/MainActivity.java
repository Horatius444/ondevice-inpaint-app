package com.example.inpaint;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OnnxTensor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;

/**
 * Inpaint Pro UI · LaMa fp32 端侧修复
 *
 * 流程：选图 → 涂抹 → 修复 → 拖动中线对比 → 保存
 */
public class MainActivity extends Activity {
    private static final int[] INPUT_SIZES = {512, 384, 256};
    private int modelInputSize = 384;
    private static final int PAD = 200;
    private static final String MODEL_NAME = "lama_fp32.onnx";

    private CompareView canvas;
    private Button btnPick, btnRepair, btnSave;
    private Button toolBrush, toolQuality, toolClear;
    private ImageButton toolUndo, toolRedo;
    private TextView statRes, statTime;
    private ProgressBar progress;

    private Bitmap baseBitmap, resultBitmap;
    private OrtSession session;
    private OrtEnvironment ortEnv;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_main);

        canvas = (CompareView) findViewById(R.id.canvas);
        btnPick = (Button) findViewById(R.id.btn_pick);
        btnRepair = (Button) findViewById(R.id.btn_repair);
        btnSave = (Button) findViewById(R.id.btn_save);
        toolBrush = (Button) findViewById(R.id.tool_brush);
        toolUndo = (ImageButton) findViewById(R.id.tool_undo);
        toolRedo = (ImageButton) findViewById(R.id.tool_redo);
        toolClear = (Button) findViewById(R.id.tool_clear);
        toolQuality = (Button) findViewById(R.id.tool_quality);
        toolQuality.setText("画质 " + modelInputSize);
        statRes = (TextView) findViewById(R.id.stat_res);
        statTime = (TextView) findViewById(R.id.stat_time);
        progress = (ProgressBar) findViewById(R.id.progress);

        canvas.setOnHistoryChangedListener(new CompareView.OnHistoryChangedListener() {
            public void onHistoryChanged(boolean canUndo, boolean canRedo) {
                if (toolUndo != null) toolUndo.setAlpha(canUndo ? 1f : 0.35f);
                if (toolRedo != null) toolRedo.setAlpha(canRedo ? 1f : 0.35f);
            }
        });
        toolUndo.setAlpha(0.35f);
        toolRedo.setAlpha(0.35f);

        // 后台加载模型
        new Thread(new Runnable() { public void run() {
            try {
                ortEnv = OrtEnvironment.getEnvironment();
                File mf = new File(getFilesDir(), MODEL_NAME);
                File vf = new File(getFilesDir(), "model_version");
                String newVer = "";
                try (InputStream vis = getAssets().open("model_version")) {
                    byte[] vb = new byte[32]; int vn = vis.read(vb);
                    if (vn > 0) newVer = new String(vb, 0, vn).trim();
                }
                String curVer = "";
                try (java.io.FileInputStream fis = new java.io.FileInputStream(vf)) {
                    byte[] vb = new byte[(int) vf.length()]; fis.read(vb);
                    curVer = new String(vb).trim();
                } catch (Exception e) { /* missing */ }
                final boolean firstLoad = curVer.isEmpty() && !mf.exists();
                if (!mf.exists() || !curVer.equals(newVer)) {
                    final String msg = firstLoad ? "首次加载模型（约 200MB）..." : "更新模型（约 200MB）...";
                    runOnUiThread(new Runnable() { public void run() { toast(msg); }});
                    try (InputStream is = getAssets().open(MODEL_NAME);
                         OutputStream os = new FileOutputStream(mf)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                    }
                    try (java.io.FileWriter w = new java.io.FileWriter(vf)) { w.write(newVer); }
                }
                OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
                opts.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors());
                opts.addXnnpack(new java.util.HashMap<String, String>());
                session = ortEnv.createSession(mf.getAbsolutePath(), opts);
                opts.close();
                runOnUiThread(new Runnable() { public void run() { toast("模型已就绪"); }});
            } catch (Throwable e) {
                e.printStackTrace();
                runOnUiThread(new Runnable() { public void run() { toast("模型加载失败: " + e.getMessage()); }});
            }
        }}).start();

        btnPick.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { pick(); } });
        btnRepair.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { runRepair(); } });
        btnSave.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            Bitmap toSave = (resultBitmap != null) ? resultBitmap : baseBitmap;
            if (toSave == null) { toast("先选一张图"); return; }
            MediaStoreSaver.save(MainActivity.this, toSave,
                    "inpaint_" + System.currentTimeMillis() + ".png");
            toast("已保存到相册");
        }});

        toolBrush.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            int sz = canvas.cycleBrushSize();
            toolBrush.setText("笔刷 " + sz);
        }});
        toolUndo.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            canvas.undo();
        }});
        toolRedo.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            canvas.redo();
        }});
        toolClear.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            canvas.resetAll();
        }});
        toolQuality.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            int idx = 0;
            for (int i = 0; i < INPUT_SIZES.length; i++) if (INPUT_SIZES[i] == modelInputSize) { idx = i; break; }
            idx = (idx + 1) % INPUT_SIZES.length;
            modelInputSize = INPUT_SIZES[idx];
            toolQuality.setText("画质 " + modelInputSize);
            String tip = (modelInputSize == 512) ? "最清晰" : (modelInputSize == 384) ? "均衡" : "更快，质量略降";
            toast("画质 " + modelInputSize + " · " + tip);
        }});
    }

    private void pick() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        startActivityForResult(Intent.createChooser(i, "选图"), 1);
    }

    @Override
    protected void onActivityResult(int code, int res, Intent data) {
        super.onActivityResult(code, res, data);
        if (code != 1 || res != RESULT_OK || data == null || data.getData() == null) return;
        try {
            InputStream is = getContentResolver().openInputStream(data.getData());
            Bitmap bmp = BitmapFactory.decodeStream(is);
            if (is != null) is.close();
            if (bmp == null) { toast("无法解码该图片"); return; }
            baseBitmap = bmp;
            resultBitmap = null;
            canvas.setBaseBitmap(bmp);
            statRes.setText(bmp.getWidth() + "×" + bmp.getHeight());
            statTime.setText("—");
        } catch (Exception e) {
            e.printStackTrace();
            toast("读图失败: " + e.getMessage());
        }
    }

    private void runRepair() {
        if (baseBitmap == null) { toast("先选一张图"); return; }
        if (session == null) { toast("模型加载中，稍后再试"); return; }
        final int W = baseBitmap.getWidth(), H = baseBitmap.getHeight();
        final int[] px = new int[W * H];
        baseBitmap.getPixels(px, 0, W, 0, 0, W, H);
        final int[] drawn = canvas.getMaskPixels(W, H);
        int drawnCount = 0;
        for (int d : drawn) if (d > 10) drawnCount++;
        if (drawnCount == 0) { toast("先用手指涂抹要去掉的水印"); return; }

        btnRepair.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        final long t0 = System.currentTimeMillis();

        new Thread(new Runnable() { public void run() {
            try {
                int minX = W, minY = H, maxX = -1, maxY = -1;
                for (int y = 0; y < H; y++) {
                    for (int x = 0; x < W; x++) {
                        if (drawn[y * W + x] > 10) {
                            if (x < minX) minX = x;
                            if (x > maxX) maxX = x;
                            if (y < minY) minY = y;
                            if (y > maxY) maxY = y;
                        }
                    }
                }
                int y1 = Math.max(0, minY - PAD);
                int y2 = Math.min(H, maxY + PAD);
                int x1 = Math.max(0, minX - PAD);
                int x2 = Math.min(W, maxX + PAD);
                int cropW = x2 - x1, cropH = y2 - y1;

                int[] cropPx = new int[cropW * cropH];
                for (int y = 0; y < cropH; y++)
                    for (int x = 0; x < cropW; x++)
                        cropPx[y * cropW + x] = px[(y + y1) * W + (x + x1)];
                Bitmap cropBmp = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888);
                cropBmp.setPixels(cropPx, 0, cropW, 0, 0, cropW, cropH);
                Bitmap img512 = Bitmap.createScaledBitmap(cropBmp, modelInputSize, modelInputSize, true);
                int[] ip512 = new int[modelInputSize * modelInputSize];
                img512.getPixels(ip512, 0, modelInputSize, 0, 0, modelInputSize, modelInputSize);

                int[] mp = new int[cropW * cropH];
                for (int y = 0; y < cropH; y++)
                    for (int x = 0; x < cropW; x++)
                        mp[y * cropW + x] = drawn[(y + y1) * W + (x + x1)] > 10 ? 0xFFFFFFFF : 0xFF000000;
                Bitmap maskBmp = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888);
                maskBmp.setPixels(mp, 0, cropW, 0, 0, cropW, cropH);
                Bitmap mask512 = Bitmap.createScaledBitmap(maskBmp, modelInputSize, modelInputSize, true);
                int[] mp512 = new int[modelInputSize * modelInputSize];
                mask512.getPixels(mp512, 0, modelInputSize, 0, 0, modelInputSize, modelInputSize);

                float[] imgF = new float[3 * modelInputSize * modelInputSize];
                float[] mskF = new float[modelInputSize * modelInputSize];
                for (int y = 0; y < modelInputSize; y++) {
                    for (int x = 0; x < modelInputSize; x++) {
                        int p = ip512[y * modelInputSize + x];
                        int off = y * modelInputSize + x;
                        imgF[off] = ((p >> 16) & 0xFF) / 255f;
                        imgF[modelInputSize * modelInputSize + off] = ((p >> 8) & 0xFF) / 255f;
                        imgF[2 * modelInputSize * modelInputSize + off] = (p & 0xFF) / 255f;
                        int m = mp512[off] & 0xFF;
                        mskF[off] = m > 10 ? 1f : 0f;
                    }
                }

                FloatBuffer imgBuf = ByteBuffer.allocateDirect(imgF.length * 4)
                        .order(ByteOrder.nativeOrder()).asFloatBuffer();
                imgBuf.put(imgF); imgBuf.rewind();
                FloatBuffer mskBuf = ByteBuffer.allocateDirect(mskF.length * 4)
                        .order(ByteOrder.nativeOrder()).asFloatBuffer();
                mskBuf.put(mskF); mskBuf.rewind();

                OnnxTensor imgTensor = OnnxTensor.createTensor(ortEnv, imgBuf,
                        new long[]{1, 3, modelInputSize, modelInputSize});
                OnnxTensor maskTensor = OnnxTensor.createTensor(ortEnv, mskBuf,
                        new long[]{1, 1, modelInputSize, modelInputSize});

                HashMap<String, OnnxTensor> inputs = new HashMap<>();
                inputs.put("image", imgTensor);
                inputs.put("mask", maskTensor);

                OrtSession.Result results = session.run(inputs);
                Object outObj = results.get(0).getValue();
                imgTensor.close(); maskTensor.close();

                float[] outF = new float[3 * modelInputSize * modelInputSize];
                if (outObj instanceof float[][][][]) {
                    float[][][][] a = (float[][][][]) outObj;
                    for (int c = 0; c < 3; c++)
                        for (int y = 0; y < modelInputSize; y++)
                            for (int x = 0; x < modelInputSize; x++)
                                outF[c * modelInputSize * modelInputSize + y * modelInputSize + x] = a[0][c][y][x];
                } else if (outObj instanceof FloatBuffer) {
                    FloatBuffer bb = (FloatBuffer) outObj;
                    bb.rewind(); bb.get(outF);
                } else if (outObj instanceof ByteBuffer) {
                    ByteBuffer bb = (ByteBuffer) outObj;
                    bb.rewind(); bb.asFloatBuffer().get(outF);
                }

                int[] out512 = new int[modelInputSize * modelInputSize];
                for (int y = 0; y < modelInputSize; y++) {
                    for (int x = 0; x < modelInputSize; x++) {
                        int off = y * modelInputSize + x;
                        int r = clamp((int) outF[off]);
                        int g = clamp((int) outF[modelInputSize * modelInputSize + off]);
                        int b = clamp((int) outF[2 * modelInputSize * modelInputSize + off]);
                        out512[off] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    }
                }
                Bitmap outBmp512 = Bitmap.createBitmap(modelInputSize, modelInputSize, Bitmap.Config.ARGB_8888);
                outBmp512.setPixels(out512, 0, modelInputSize, 0, 0, modelInputSize, modelInputSize);
                Bitmap outFull = Bitmap.createScaledBitmap(outBmp512, cropW, cropH, true);

                int[] maskFullPx = new int[cropW * cropH];
                maskBmp.setPixels(mp, 0, cropW, 0, 0, cropW, cropH);
                Bitmap maskFullBmp = Bitmap.createScaledBitmap(maskBmp, cropW, cropH, true);
                maskFullBmp.getPixels(maskFullPx, 0, cropW, 0, 0, cropW, cropH);
                float[] maskSmooth = boxBlur(maskFullPx, cropW, cropH, 5);

                int[] outFullPx = new int[cropW * cropH];
                outFull.getPixels(outFullPx, 0, cropW, 0, 0, cropW, cropH);
                int[] blendPx = new int[cropW * cropH];
                for (int i = 0; i < cropPx.length; i++) {
                    float ms = maskSmooth[i];
                    int r = (int) (((outFullPx[i] >> 16) & 0xFF) * ms + ((cropPx[i] >> 16) & 0xFF) * (1 - ms));
                    int g = (int) (((outFullPx[i] >> 8) & 0xFF) * ms + ((cropPx[i] >> 8) & 0xFF) * (1 - ms));
                    int b = (int) ((outFullPx[i] & 0xFF) * ms + (cropPx[i] & 0xFF) * (1 - ms));
                    blendPx[i] = 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
                }

                final int[] resultPx = px.clone();
                for (int y = 0; y < cropH; y++)
                    for (int x = 0; x < cropW; x++)
                        resultPx[(y + y1) * W + (x + x1)] = blendPx[y * cropW + x];

                final Bitmap outBmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
                outBmp.setPixels(resultPx, 0, W, 0, 0, W, H);

                final long dt = System.currentTimeMillis() - t0;

                runOnUiThread(new Runnable() { public void run() {
                    resultBitmap = outBmp;
                    canvas.setResultBitmap(outBmp);
                    statRes.setText(W + "×" + H);
                    statTime.setText((dt / 1000f) + "s");
                    btnRepair.setEnabled(true);
                    progress.setVisibility(View.INVISIBLE);
                    toast("修复完成 " + (dt / 1000f) + "s");
                }});
            } catch (Throwable e) {
                e.printStackTrace();
                final String msg = e.getMessage();
                runOnUiThread(new Runnable() { public void run() {
                    btnRepair.setEnabled(true);
                    progress.setVisibility(View.INVISIBLE);
                    toast("推理失败: " + msg);
                }});
            }
        }}).start();
    }

    private static int clamp(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

    private static float[] boxBlur(int[] argb, int w, int h, int radius) {
        float[] out = new float[w * h];
        int r = radius / 2;
        int area = (2 * r + 1) * (2 * r + 1);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int sum = 0;
                for (int dy = -r; dy <= r; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= h) continue;
                    for (int dx = -r; dx <= r; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= w) continue;
                        sum += (argb[yy * w + xx] >> 16) & 0xFF;
                    }
                }
                out[y * w + x] = (sum / (float) area) / 255f;
            }
        }
        return out;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}