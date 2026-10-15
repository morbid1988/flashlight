package com.example.torch;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/**
 * The forest photo. Still by default; in "alive" mode the whole picture breathes and sways
 * (trees, branches, leaves), colours drift, ghost trails and glowing halos float over it.
 * It can also show a soft transparent water ripple (used when the moss sphere comes back).
 */
public class PsychForestView extends View {

    private static final int COLS = 22;
    private static final int ROWS = 44;

    private Bitmap bmp;
    private final float density;
    private final float[] orig = new float[(COLS + 1) * (ROWS + 1) * 2];
    private final float[] verts = new float[(COLS + 1) * (ROWS + 1) * 2];

    private final Paint basePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint ghostPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] hsv = new float[3];
    private Shader unitGlow;

    private float intensity = 0f;     // 0 = still photo, 1 = fully alive
    private ValueAnimator intensityAnim;
    private float ripple = 0f;        // 0..1 progress of the water ripple, 0 or 1 = off
    private float rippleX, rippleY;
    private final long t0 = System.nanoTime();

    public PsychForestView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        ghostPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        haloPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        ringPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        unitGlow = new RadialGradient(0f, 0f, 1f,
                new int[]{0xFFFFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP);
        haloPaint.setShader(unitGlow);
    }

    public void setAlive(boolean alive) {
        if (intensityAnim != null) intensityAnim.cancel();
        intensityAnim = ValueAnimator.ofFloat(intensity, alive ? 1f : 0f);
        intensityAnim.setDuration(alive ? 1800 : 900);
        intensityAnim.addUpdateListener(a -> {
            intensity = (Float) a.getAnimatedValue();
            invalidate();
        });
        intensityAnim.start();
    }

    /** p in (0,1) runs the ripple from (x,y); anything else switches it off. */
    public void setRipple(float p, float x, float y) {
        ripple = p;
        rippleX = x;
        rippleY = y;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) return;
        Bitmap src = BitmapFactory.decodeResource(getResources(), R.drawable.forest_bg);
        // centre-crop to the view size
        float s = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Matrix m = new Matrix();
        m.postScale(s, s);
        m.postTranslate((w - src.getWidth() * s) / 2f, (h - src.getHeight() * s) / 2f);
        cv.drawBitmap(src, m, new Paint(Paint.FILTER_BITMAP_FLAG));
        if (bmp != null) bmp.recycle();
        bmp = out;
        src.recycle();
        int i = 0;
        for (int r = 0; r <= ROWS; r++) {
            for (int c = 0; c <= COLS; c++) {
                orig[i++] = w * c / (float) COLS;
                orig[i++] = h * r / (float) ROWS;
            }
        }
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas canvas) {
        if (bmp == null) return;
        boolean rippling = ripple > 0f && ripple < 1f;
        boolean fx = intensity > 0.002f || rippling;
        if (!fx) {
            canvas.drawBitmap(bmp, 0f, 0f, null);
            return;
        }
        float t = (System.nanoTime() - t0) / 1e9f;
        float w = getWidth();
        float h = getHeight();

        // base layer: swaying, slowly colour-shifting
        float zoom = 1f + 0.07f * Math.max(intensity, rippling ? 1f - ripple : 0f);
        float hue = intensity * (70f * (float) Math.sin(t * 0.6f) + 40f * (float) Math.sin(t * 0.23f + 1f));
        basePaint.setColorFilter(filter(hue, 1f + 0.7f * intensity, 1f + 0.22f * intensity));
        buildMesh(t, 1f);
        canvas.save();
        canvas.scale(zoom, zoom, w / 2f, h / 2f);
        canvas.drawBitmapMesh(bmp, COLS, ROWS, verts, 0, null, 0, basePaint);
        canvas.restore();

        if (intensity > 0.002f) {
            // ghost / trail layer: a delayed, differently tinted copy added on top
            ghostPaint.setColorFilter(filter(hue + 130f + 40f * (float) Math.sin(t * 0.4f), 1.9f, 1.1f));
            ghostPaint.setAlpha((int) (255 * 0.38f * intensity));
            buildMesh(t - 0.7f, 1.35f);
            float gz = zoom * (1.02f + 0.025f * (float) Math.sin(t * 0.9f));
            canvas.save();
            canvas.scale(gz, gz, w / 2f, h / 2f);
            canvas.drawBitmapMesh(bmp, COLS, ROWS, verts, 0, null, 0, ghostPaint);
            canvas.restore();

            // wandering light halos
            for (int i = 0; i < 6; i++) {
                float k = 1f + i * 0.37f;
                float x = w * (0.5f + 0.42f * (float) Math.sin(t * 0.29f * k + i * 1.9f));
                float y = h * (0.5f + 0.40f * (float) Math.cos(t * 0.23f * k + i * 2.7f));
                float r = w * (0.5f + 0.18f * (float) Math.sin(t * 0.7f + i));
                hsv[0] = (t * 28f + i * 60f) % 360f;
                hsv[1] = 0.7f;
                hsv[2] = 1f;
                haloPaint.setColorFilter(new PorterDuffColorFilter(Color.HSVToColor(hsv), PorterDuff.Mode.SRC_IN));
                haloPaint.setAlpha((int) (255 * 0.34f * intensity * (0.65f + 0.35f * (float) Math.sin(t * 1.1f + i * 2f))));
                canvas.save();
                canvas.translate(x, y);
                canvas.scale(r, r);
                canvas.drawCircle(0f, 0f, 1f, haloPaint);
                canvas.restore();
            }

            // pulsing aura rings that expand from a drifting centre
            float ax = w * (0.5f + 0.15f * (float) Math.sin(t * 0.17f));
            float ay = h * (0.45f + 0.10f * (float) Math.cos(t * 0.21f));
            float diag = (float) Math.hypot(w, h);
            for (int i = 0; i < 3; i++) {
                float ph = (t * 0.16f + i / 3f) % 1f;
                hsv[0] = (t * 40f + i * 120f) % 360f;
                hsv[1] = 0.55f;
                hsv[2] = 1f;
                drawRing(canvas, ax, ay, ph * diag * 0.8f + 8f * density, 34f * density,
                        Color.HSVToColor(hsv), 0.22f * intensity * (1f - ph));
            }
        }

        if (rippling) {
            float er = easeOut(ripple);
            float r = er * (float) Math.hypot(getWidth(), getHeight());
            drawRing(canvas, rippleX, rippleY, r, 46f * density, 0xFFFFFFFF, 0.20f * (1f - ripple));
            drawRing(canvas, rippleX, rippleY, r * 0.62f, 30f * density, 0xFFCFFFF3, 0.12f * (1f - ripple));
        }

        postInvalidateOnAnimation();
    }

    private void drawRing(Canvas canvas, float cx, float cy, float r, float width, int color, float alpha) {
        if (alpha <= 0.004f || r <= 1f) return;
        float outer = r + width;
        float p0 = Math.max(0f, (r - width) / outer);
        float p1 = Math.min(0.999f, r / outer);
        if (p1 <= p0) return;
        ringPaint.setShader(new RadialGradient(cx, cy, outer,
                new int[]{color & 0x00FFFFFF, color, color & 0x00FFFFFF},
                new float[]{p0, p1, 1f}, Shader.TileMode.CLAMP));
        ringPaint.setAlpha((int) (255 * Math.min(1f, alpha)));
        canvas.drawCircle(cx, cy, outer, ringPaint);
    }

    private static float easeOut(float p) {
        float q = 1f - p;
        return 1f - q * q * q;
    }

    /** Displaces the mesh: tree sway, leaf flutter, breathing and (optionally) the water ripple. */
    private void buildMesh(float t, float strength) {
        float w = getWidth();
        float h = getHeight();
        float sw = intensity * density * strength;
        boolean rippling = ripple > 0f && ripple < 1f;
        float rr = 0f, amp = 0f, lambda = 70f * density, width = 95f * density;
        if (rippling) {
            rr = easeOut(ripple) * (float) Math.hypot(w, h);
            amp = (1f - ripple) * (1f - ripple) * 13f * density * Math.min(1f, ripple * 8f);
        }
        int n = orig.length;
        for (int i = 0; i < n; i += 2) {
            float x = orig[i];
            float y = orig[i + 1];
            float u = x / w;
            float v = y / h;
            float top = 1f - v;
            float base = 0.4f + 0.6f * top * top;
            float dx = sw * (9f * base * (float) Math.sin(t * 0.9f + v * 3f + u * 2f)
                    + 5f * (float) Math.sin(t * 1.7f + u * 11f + v * 6f)
                    + 2.5f * (float) Math.sin(t * 3.1f + u * 23f - v * 17f));
            float dy = sw * (4f * (float) Math.sin(t * 1.2f + u * 8f + v * 5f)
                    + 2f * (float) Math.sin(t * 2.3f + u * 17f - v * 9f));
            if (rippling) {
                float ddx = x - rippleX;
                float ddy = y - rippleY;
                float d = (float) Math.sqrt(ddx * ddx + ddy * ddy) + 0.001f;
                float e = (d - rr) / width;
                float disp = amp * (float) Math.exp(-e * e)
                        * (float) Math.sin((d - rr) * 6.2831853f / lambda);
                float e2 = (d - rr * 0.62f) / width;
                disp += 0.5f * amp * (float) Math.exp(-e2 * e2)
                        * (float) Math.sin((d - rr * 0.62f) * 6.2831853f / lambda);
                dx += disp * ddx / d;
                dy += disp * ddy / d;
            }
            verts[i] = x + dx;
            verts[i + 1] = y + dy;
        }
    }

    private ColorMatrixColorFilter filter(float hueDeg, float sat, float contrast) {
        double a = Math.toRadians(hueDeg);
        float c = (float) Math.cos(a);
        float s = (float) Math.sin(a);
        float lr = 0.213f, lg = 0.715f, lb = 0.072f;
        ColorMatrix hueM = new ColorMatrix(new float[]{
                lr + c * (1 - lr) + s * (-lr), lg + c * (-lg) + s * (-lg), lb + c * (-lb) + s * (1 - lb), 0, 0,
                lr + c * (-lr) + s * 0.143f, lg + c * (1 - lg) + s * 0.140f, lb + c * (-lb) + s * (-0.283f), 0, 0,
                lr + c * (-lr) + s * (-(1 - lr)), lg + c * (-lg) + s * lg, lb + c * (1 - lb) + s * lb, 0, 0,
                0, 0, 0, 1, 0});
        ColorMatrix satM = new ColorMatrix();
        satM.setSaturation(sat);
        float off = 128f * (1f - contrast);
        ColorMatrix conM = new ColorMatrix(new float[]{
                contrast, 0, 0, 0, off,
                0, contrast, 0, 0, off,
                0, 0, contrast, 0, off,
                0, 0, 0, 1, 0});
        satM.postConcat(hueM);
        satM.postConcat(conM);
        return new ColorMatrixColorFilter(satM);
    }
                            }
