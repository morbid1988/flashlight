package com.example.torch;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import java.util.Random;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private CameraManager cameraManager;
    private String torchCameraId;
    private boolean torchOn = false;

    private static final int ST_OFF = 0, ST_ON = 1, ST_ALIVE = 2, ST_RESTORING = 3;
    private int state = ST_OFF;
    private FrameLayout rootView;
    private float holderSizePx;
    private ValueAnimator restoreAnim;
    private MediaPlayer music;
    private final Random random = new Random();

    private PsychForestView forestView;
    private VideoForestView glView;
    private MagicOverlayView magic;
    private boolean ending = false;
    private View whiteCircle;
    private ImageView mossSphere;
    private View shadow;
    private FrameLayout sphereHolder;
    private ValueAnimator floatAnim;
    private float amplitude;

    private final CameraManager.TorchCallback torchCallback = new CameraManager.TorchCallback() {
        @Override
        public void onTorchModeChanged(String cameraId, boolean enabled) {
            if (cameraId.equals(torchCameraId)) {
                torchOn = enabled;
                runOnUiThread(() -> {
                    if (enabled && state == ST_OFF) {
                        state = ST_ON;
                        applyState(true);
                    } else if (!enabled && state == ST_ON) {
                        state = ST_OFF;
                        applyState(false);
                    }
                });
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        float density = getResources().getDisplayMetrics().density;
        int holderSize = (int) (170 * density);
        int circleSize = (int) (140 * density);
        amplitude = 14 * density;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // Forest photo: covers the whole screen, hidden until the torch is on
        rootView = root;
        holderSizePx = holderSize;
        forestView = new PsychForestView(this);
        forestView.setAlpha(0f);
        forestView.setOnClickListener(v -> onForestTap());
        root.addView(forestView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // the living-forest journey (video + shader), hidden until the forest comes alive
        glView = new VideoForestView(this);
        glView.setVisibility(View.GONE);
        glView.setOnClickListener(v -> onForestTap());
        root.addView(glView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // magic mushrooms, moss light and spores on top of the video
        magic = new MagicOverlayView(this);
        magic.setVisibility(View.GONE);
        magic.setClickable(false);
        root.addView(magic, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // Soft shadow on the "ground" under the floating sphere
        shadow = new View(this);
        GradientDrawable sh = new GradientDrawable();
        sh.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        sh.setGradientRadius(holderSize / 2f);
        sh.setColors(new int[]{0xAA000000, 0x00000000});
        shadow.setBackground(sh);
        shadow.setAlpha(0f);
        shadow.setScaleY(0.22f);
        shadow.setTranslationY(holderSize * 0.80f);
        root.addView(shadow, new FrameLayout.LayoutParams(holderSize, holderSize, Gravity.CENTER));

        // The button: white circle (off) and moss sphere (on) share one holder
        sphereHolder = new FrameLayout(this);

        whiteCircle = new View(this);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.WHITE);
        whiteCircle.setBackground(circle);
        sphereHolder.addView(whiteCircle,
                new FrameLayout.LayoutParams(circleSize, circleSize, Gravity.CENTER));

        mossSphere = new ImageView(this);
        mossSphere.setImageResource(R.drawable.moss_sphere);
        mossSphere.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mossSphere.setAlpha(0f);
        sphereHolder.addView(mossSphere, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        root.addView(sphereHolder, new FrameLayout.LayoutParams(holderSize, holderSize, Gravity.CENTER));

        setContentView(root);

        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        torchCameraId = findTorchCamera();

        if (torchCameraId == null) {
            Toast.makeText(this, "No flash found on this device", Toast.LENGTH_LONG).show();
        } else {
            cameraManager.registerTorchCallback(torchCallback, null);
        }

        sphereHolder.setOnClickListener(v -> {
            if (state == ST_ALIVE) {
                if (!ending) tapEnd();
            } else if (state == ST_ON || state == ST_OFF) {
                toggleTorch();
            }
        });
    }

    private void applyState(boolean on) {
        forestView.animate().alpha(on ? 1f : 0f).setDuration(600).start();
        mossSphere.animate().alpha(on ? 1f : 0f).setDuration(450).start();
        whiteCircle.animate().alpha(on ? 0f : 1f).setDuration(450).start();
        shadow.animate().alpha(on ? 1f : 0f).setDuration(600).start();
        if (on) {
            startFloating();
        } else {
            stopFloating();
        }
    }

    // Tap on the forest while the torch is on: the forest comes alive, torch goes off,
    // the moss sphere dissolves over 3 seconds. Tap again: everything returns.
    private void onForestTap() {
        if (state == ST_ON) {
            goAlive();
        } else if (state == ST_ALIVE && !ending) {
            tapEnd();
        }
    }

    // Music: res/raw/forest_music.(mp3|ogg|m4a|wav). Starts from the beginning every time the forest comes alive.
    private void startMusic() {
        stopMusic();
        int id = getResources().getIdentifier("forest_music", "raw", getPackageName());
        if (id == 0) {
            id = getResources().getIdentifier("psy_music", "raw", getPackageName());
        }
        if (id == 0) {
            return;
        }
        try {
            music = MediaPlayer.create(this, id);
            if (music != null) {
                music.setLooping(false);
                music.start();
            }
        } catch (RuntimeException e) {
            music = null;
        }
    }

    private void stopMusic() {
        if (music != null) {
            try {
                music.stop();
            } catch (RuntimeException e) {
                // already stopped
            }
            music.release();
            music = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // leaving the app during the journey ends it: back to the still forest
        if (state == ST_ALIVE && !ending) {
            ending = true;
            stopMusic();
            glView.stopNow();
            glView.setVisibility(View.GONE);
            magic.setVisibility(View.GONE);
            restoreForest();
        }
        glView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        glView.onResume();
    }

    private void goAlive() {
        state = ST_ALIVE;
        ending = false;
        startMusic();
        glView.setVisibility(View.VISIBLE);
        magic.setVisibility(View.VISIBLE);
        glView.startJourney(this::onVideoCompleted);
        if (torchCameraId != null) {
            try {
                cameraManager.setTorchMode(torchCameraId, false);
            } catch (CameraAccessException | IllegalArgumentException e) {
                // ignore: the visuals still run
            }
        }
        mossSphere.animate().cancel();
        shadow.animate().cancel();
        mossSphere.animate().alpha(0f).setDuration(3000)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(() -> {
                    if (state == ST_ALIVE) stopFloating();
                }).start();
        shadow.animate().alpha(0f).setDuration(3000).start();
    }

    // the video reached its end: the tree sways 3 s, goes still, the sphere grows back
    private void onVideoCompleted() {
        runOnUiThread(() -> {
            if (state != ST_ALIVE || ending) return;
            ending = true;
            magic.setVisibility(View.GONE);
            glView.endJourney(true, this::afterJourney);
        });
    }

    // second tap: everything settles back at once
    private void tapEnd() {
        ending = true;
        stopMusic();
        magic.setVisibility(View.GONE);
        glView.endJourney(false, this::afterJourney);
    }

    private void afterJourney() {
        stopMusic();
        glView.setVisibility(View.GONE);
        restoreForest();
    }

    private void restoreForest() {
        state = ST_RESTORING;
        stopMusic();
        mossSphere.animate().cancel();
        shadow.animate().cancel();
        stopFloating();
        sphereHolder.animate().cancel();
        forestView.setAlive(false);
        if (torchCameraId != null) {
            try {
                cameraManager.setTorchMode(torchCameraId, true);
            } catch (CameraAccessException | IllegalArgumentException e) {
                // ignore
            }
        }

        final float w = rootView.getWidth();
        final float h = rootView.getHeight();
        // the sphere is born as a single pixel somewhere among the plants near the ground
        final float startX = w * (0.28f + 0.44f * random.nextFloat());
        final float startY = h * (0.72f + 0.12f * random.nextFloat());
        final float startTx = startX - w / 2f;
        final float startTy = startY - h / 2f;
        final float minScale = 1f / holderSizePx;
        final OvershootInterpolator overshoot = new OvershootInterpolator(0.9f);

        mossSphere.setAlpha(1f);
        whiteCircle.setAlpha(0f);
        sphereHolder.setScaleX(minScale);
        sphereHolder.setScaleY(minScale);
        sphereHolder.setTranslationX(startTx);
        sphereHolder.setTranslationY(startTy);

        if (restoreAnim != null) restoreAnim.cancel();
        restoreAnim = ValueAnimator.ofFloat(0f, 1f);
        restoreAnim.setDuration(1900);
        restoreAnim.addUpdateListener(a -> {
            float lin = (Float) a.getAnimatedValue();
            float f = overshoot.getInterpolation(Math.min(1f, lin / 0.75f));
            float sc = minScale + (1f - minScale) * f;
            float tx = startTx * (1f - f);
            float ty = startTy * (1f - f);
            sphereHolder.setScaleX(sc);
            sphereHolder.setScaleY(sc);
            sphereHolder.setTranslationX(tx);
            sphereHolder.setTranslationY(ty);
            shadow.setAlpha(Math.min(1f, f));
            shadow.setScaleX(sc);
            shadow.setScaleY(0.22f * sc);
            shadow.setTranslationX(tx);
            shadow.setTranslationY(ty + holderSizePx * 0.80f * sc);
            // a very soft, transparent water ripple travels out from the sphere over the whole screen
            forestView.setRipple(lin, w / 2f + tx, h / 2f + ty);
        });
        restoreAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled = false;

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                forestView.setRipple(0f, 0f, 0f);
                if (cancelled) return;
                sphereHolder.setScaleX(1f);
                sphereHolder.setScaleY(1f);
                sphereHolder.setTranslationX(0f);
                shadow.setTranslationX(0f);
                shadow.setTranslationY(holderSizePx * 0.80f);
                shadow.setScaleX(1f);
                shadow.setScaleY(0.22f);
                shadow.setAlpha(1f);
                state = ST_ON;
                ending = false;
                startFloating();
            }
        });
        restoreAnim.start();
    }

    private void startFloating() {
        if (floatAnim != null) {
            floatAnim.cancel();
        }
        floatAnim = ValueAnimator.ofFloat(0f, 1f);
        floatAnim.setDuration(2300);
        floatAnim.setRepeatCount(ValueAnimator.INFINITE);
        floatAnim.setRepeatMode(ValueAnimator.REVERSE);
        floatAnim.setInterpolator(new AccelerateDecelerateInterpolator());
        floatAnim.addUpdateListener(a -> {
            float f = (Float) a.getAnimatedValue();
            sphereHolder.setTranslationY(-amplitude * f);
            float s = 1f - 0.2f * f;          // shadow shrinks as the sphere rises
            shadow.setScaleX(s);
            shadow.setScaleY(0.22f * s);
        });
        floatAnim.start();
    }

    private void stopFloating() {
        if (floatAnim != null) {
            floatAnim.cancel();
            floatAnim = null;
        }
        sphereHolder.animate().translationY(0f).setDuration(300).start();
        shadow.setScaleX(1f);
        shadow.setScaleY(0.22f);
    }

    private String findTorchCamera() {
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                Boolean hasFlash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(hasFlash)
                        && facing != null
                        && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return id;
                }
            }
        } catch (CameraAccessException e) {
            // fall through
        }
        return null;
    }

    private void toggleTorch() {
        if (torchCameraId == null) {
            return;
        }
        try {
            cameraManager.setTorchMode(torchCameraId, !torchOn);
        } catch (CameraAccessException | IllegalArgumentException e) {
            Toast.makeText(this, "Could not control the flash", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (floatAnim != null) {
            floatAnim.cancel();
        }
        if (restoreAnim != null) {
            restoreAnim.cancel();
        }
        stopMusic();
        glView.stopNow();
        if (torchCameraId != null) {
            cameraManager.unregisterTorchCallback(torchCallback);
        }
    }
}
