package com.example.torch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import java.util.Random;

/**
 * Glowing magic mushrooms, moss light and drifting spores, drawn over the forest video.
 * The mushrooms sit along the "ground line" of the video composition and breathe/sway.
 */
public class MagicOverlayView extends View {

    private static final float CROP_W = 0.62f;

    private static final class Mush {
        float x;          // 0..1 across the screen
        float dy;         // vertical offset from the ground line, in units of screen width
        float size;       // in dp
        float hue;
        float phase;
    }

    private static final class Spore {
        float x, y, speed, size, phase, hue;
    }

    private final float density;
    private final Mush[] mushrooms = new Mush[16];
    private final Spore[] spores = new Spore[70];
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shader unit = new RadialGradient(0f, 0f, 1f,
            new int[]{0xFFFFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP);
    private final float[] hsv = new float[3];
    private final RectF rect = new RectF();
    private final long t0 = System.nanoTime();

    public MagicOverlayView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        glow.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        glow.setShader(unit);
        spot.setColor(0xCCFFFFFF);
        Random r = new Random(11);
        for (int i = 0; i < mushrooms.length; i++) {
            Mush m = new Mush();
            m.x = 0.04f + 0.92f * r.nextFloat();
            m.dy = -0.20f + 0.34f * r.nextFloat();   // above and below the ground line
            m.size = 15f + 26f * r.nextFloat();
            m.hue = r.nextFloat() * 360f;
            m.phase = r.nextFloat() * 6.28f;
            mushrooms[i] = m;
        }
        for (int i = 0; i < spores.length; i++) {
            Spore s = new Spore();
            s.x = r.nextFloat();
            s.y = r.nextFloat();
            s.speed = 0.012f + 0.03f * r.nextFloat();
            s.size = 2f + 5f * r.nextFloat();
            s.phase = r.nextFloat() * 6.28f;
            s.hue = r.nextFloat() * 360f;
            spores[i] = s;
        }
    }

    private int hue(float h, float s, float v) {
        hsv[0] = ((h % 360f) + 360f) % 360f;
        hsv[1] = s;
        hsv[2] = v;
        return Color.HSVToColor(hsv);
    }

    private void blob(Canvas c, float x, float y, float rx, float ry, int color, int alpha) {
        glow.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        glow.setAlpha(Math.max(0, Math.min(255, alpha)));
        c.save();
        c.translate(x, y);
        c.scale(rx, ry);
        c.drawCircle(0f, 0f, 1f, glow);
        c.restore();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float vt = VideoForestView.Journey.videoTime();
        if (vt < 0f) {
            postInvalidateOnAnimation();
            return;
        }
        float vis = VideoForestView.Journey.smooth(2f, 8f, vt)
                * (1f - VideoForestView.Journey.smooth(284f, 289f, vt));
        if (vis <= 0.01f) {
            postInvalidateOnAnimation();
            return;
        }
        float w = getWidth();
        float h = getHeight();
        float t = (System.nanoTime() - t0) / 1e9f;
        float inten = 0.55f + 0.45f * VideoForestView.Journey.intensity(vt);

        // where the ground line of the video composition sits on screen
        float band = w / (CROP_W * (720f / 406f));
        float groundY = h / 2f + band / 2f - 0.02f * band * (float) Math.sin(vt * 0.13f);

        // soft moss light along the ground line
        for (int i = 0; i < 7; i++) {
            float x = w * (0.08f + 0.14f * i) + 12f * density * (float) Math.sin(t * 0.3f + i);
            float y = groundY + 0.03f * h * (float) Math.sin(t * 0.2f + i * 1.3f);
            blob(canvas, x, y, 90f * density, 38f * density,
                    hue(130f + 25f * (float) Math.sin(t * 0.1f + i), 0.75f, 1f), (int) (70 * vis * inten));
        }

        // mushrooms
        for (Mush m : mushrooms) {
            float grow = 0.78f + 0.22f * (float) Math.sin(t * 0.8f + m.phase);
            float s = m.size * density * grow * (0.75f + 0.25f * vis) * vis;
            float x = w * m.x + 5f * density * (float) Math.sin(t * 0.45f + m.phase);
            float y = groundY + m.dy * w;
            float sway = 7f * (float) Math.sin(t * 0.6f + m.phase);
            int cap = hue(m.hue + t * 22f, 0.8f, 1f);
            int capDark = hue(m.hue + t * 22f + 25f, 0.9f, 0.55f);

            // glow
            blob(canvas, x, y - s * 1.1f, s * 2.4f, s * 2.4f, cap, (int) (120 * vis * inten));

            canvas.save();
            canvas.translate(x, y);
            canvas.rotate(sway);

            // stem
            fill.setShader(null);
            fill.setColor(0xF2F4EBDD);
            rect.set(-s * 0.22f, -s * 1.05f, s * 0.22f, 0f);
            canvas.drawRoundRect(rect, s * 0.2f, s * 0.2f, fill);

            // cap
            fill.setShader(new RadialGradient(0f, -s * 1.3f, s * 1.1f,
                    new int[]{cap, capDark}, null, Shader.TileMode.CLAMP));
            rect.set(-s, -s * 1.6f, s, -s * 0.5f);
            canvas.drawOval(rect, fill);
            fill.setShader(null);

            // spots
            canvas.drawCircle(-s * 0.45f, -s * 1.25f, s * 0.12f, spot);
            canvas.drawCircle(s * 0.25f, -s * 1.45f, s * 0.15f, spot);
            canvas.drawCircle(s * 0.55f, -s * 1.15f, s * 0.09f, spot);
            canvas.restore();
        }

        // drifting spores
        for (Spore sp : spores) {
            float yy = (sp.y - sp.speed * t) % 1f;
            if (yy < 0f) yy += 1f;
            float xx = sp.x + 0.03f * (float) Math.sin(t * 0.5f + sp.phase);
            float tw = 0.5f + 0.5f * (float) Math.sin(t * 1.6f + sp.phase);
            blob(canvas, xx * w, yy * h, sp.size * density * 2.2f, sp.size * density * 2.2f,
                    hue(sp.hue + t * 15f, 0.6f, 1f), (int) (200 * vis * tw * inten));
        }

        postInvalidateOnAnimation();
    }
}
