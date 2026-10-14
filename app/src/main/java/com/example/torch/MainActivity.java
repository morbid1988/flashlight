package com.example.torch;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;

public class MainActivity extends Activity {

    private CameraManager cameraManager;
    private String torchCameraId;
    private boolean torchOn = false;
    private boolean forestMode = false;

    private FrameLayout root;
    private ImageView button;
    private ObjectAnimator floatAnimation;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final CameraManager.TorchCallback torchCallback =
            new CameraManager.TorchCallback() {
        @Override
        public void onTorchModeChanged(String cameraId, boolean enabled) {
            if (cameraId.equals(torchCameraId)) {
                torchOn = enabled;

                // هماهنگ‌سازی وضعیت رابط با وضعیت واقعی فلش
                handler.post(() -> {
                    if (!enabled && forestMode) {
                        showSimpleScene();
                    }
                });
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        float density = getResources().getDisplayMetrics().density;

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        button = new ImageView(this);
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        setWhiteButton();

        int size = (int) (140 * density);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                size, size, Gravity.CENTER
        );

        root.addView(button, lp);
        setContentView(root);

        cameraManager = (CameraManager)
                getSystemService(Context.CAMERA_SERVICE);

        torchCameraId = findTorchCamera();

        if (torchCameraId == null) {
            Toast.makeText(
                    this,
                    "No flash found on this device",
                    Toast.LENGTH_LONG
            ).show();
        } else {
            cameraManager.registerTorchCallback(torchCallback, null);
        }

        button.setOnClickListener(v -> {
            if (!forestMode) {
                // از حالت ساده به صحنه جنگل برو
                showForestScene(density);
                turnTorchOn();
            } else {
                // با تپ دوم فلش خاموش و صحنه ساده می‌شود
                turnTorchOff();
                showSimpleScene();
            }
        });
    }

    private void showForestScene(float density) {
        forestMode = true;

        root.setBackgroundResource(R.drawable.forest_bg);

        button.setBackgroundColor(Color.TRANSPARENT);
        button.setImageResource(R.drawable.moss_sphere);
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);

        int size = (int) (190 * density);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                size, size, Gravity.CENTER
        );
        button.setLayoutParams(lp);

        if (floatAnimation != null) {
            floatAnimation.cancel();
        }

        floatAnimation = ObjectAnimator.ofFloat(
                button,
                View.TRANSLATION_Y,
                -10 * density,
                10 * density
        );

        floatAnimation.setDuration(2200);
        floatAnimation.setRepeatMode(ValueAnimator.REVERSE);
        floatAnimation.setRepeatCount(ValueAnimator.INFINITE);
        floatAnimation.start();
    }

    private void showSimpleScene() {
        forestMode = false;

        if (floatAnimation != null) {
            floatAnimation.cancel();
            floatAnimation = null;
        }

        root.setBackgroundColor(Color.BLACK);
        button.setImageDrawable(null);
        button.setTranslationY(0);
        setWhiteButton();

        float density = getResources().getDisplayMetrics().density;
        int size = (int) (140 * density);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                size, size, Gravity.CENTER
        );
        button.setLayoutParams(lp);
    }

    private void setWhiteButton() {
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.WHITE);
        button.setBackground(circle);
    }

    private String findTorchCamera() {
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics c =
                        cameraManager.getCameraCharacteristics(id);

                Boolean hasFlash =
                        c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);

                Integer facing =
                        c.get(CameraCharacteristics.LENS_FACING);

                if (Boolean.TRUE.equals(hasFlash)
                        && facing != null
                        && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return id;
                }
            }
        } catch (CameraAccessException e) {
            // دوربین در دسترس نیست
        }

        return null;
    }

    private void turnTorchOn() {
        if (torchCameraId == null) {
            return;
        }

        try {
            cameraManager.setTorchMode(torchCameraId, true);
        } catch (CameraAccessException | IllegalArgumentException e) {
            Toast.makeText(
                    this,
                    "Could not turn on the flash",
                    Toast.LENGTH_SHORT
            ).show();
        }
    }

    private void turnTorchOff() {
        if (torchCameraId == null) {
            return;
        }

        try {
            cameraManager.setTorchMode(torchCameraId, false);
        } catch (CameraAccessException | IllegalArgumentException e) {
            Toast.makeText(
                    this,
                    "Could not turn off the flash",
                    Toast.LENGTH_SHORT
            ).show();
        }
    }

    @Override
    protected void onDestroy() {
        if (floatAnimation != null) {
            floatAnimation.cancel();
        }

        if (torchCameraId != null && cameraManager != null) {
            cameraManager.unregisterTorchCallback(torchCallback);
        }

        super.onDestroy();
    }
}
