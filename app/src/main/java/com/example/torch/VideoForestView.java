package com.example.torch;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * The "walk through the living forest" scene.
 * Timeline: the forest photo sways for a few seconds, then dissolves into the forest video
 * (res/raw/psy_forest.mp4, 290 s) which is drawn through a psychedelic shader.
 * At the end (or on a tap) it dissolves back into the original photo.
 */
public class VideoForestView extends GLSurfaceView {

    public interface Listener {
        void onVideoCompleted();   // the video reached its end by itself
    }

    /** Shared clock so the Java overlay (mushrooms, spores) stays in sync with the shader. */
    public static final class Journey {
        public static volatile long videoStartNs = 0L;
        public static volatile boolean active = false;

        static float smooth(float a, float b, float x) {
            float t = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
            return t * t * (3f - 2f * t);
        }

        public static float videoTime() {
            if (!active || videoStartNs == 0L) return -1f;
            return (System.nanoTime() - videoStartNs) / 1e9f;
        }

        /** How strong the "vision" is, over the 290 s journey. */
        public static float intensity(float vt) {
            if (vt < 0f) return 0f;
            float ramp = 0.25f + 0.75f * smooth(0f, 35f, vt);
            float pulse = 0.90f + 0.10f * (float) Math.sin(vt * 0.35f);
            float tail = 1f - 0.65f * smooth(266f, 284f, vt);
            return Math.max(0f, Math.min(1f, ramp * pulse * tail));
        }
    }

    private static final int M_IDLE = 0, M_INTRO = 1, M_VIDEO = 2, M_OUTRO = 3, M_DONE = 4;
    private static final float INTRO_SECONDS = 5f;
    private static final float CROP_W = 0.62f;     // fraction of the video width shown on screen

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Renderer renderer = new Renderer();

    private Listener listener;
    private MediaPlayer player;
    private boolean playerPrepared = false;
    private boolean startWhenPrepared = false;
    private Runnable outroDone;

    public VideoForestView(Context c) {
        super(c);
        setEGLContextClientVersion(2);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
    }

    // ------------------------------------------------------------ UI-thread API

    public void startJourney(Listener l) {
        listener = l;
        outroDone = null;
        renderer.beginIntro();
        Journey.active = true;
        Journey.videoStartNs = 0L;
        ui.removeCallbacksAndMessages(null);
        ui.postDelayed(this::createPlayerWhenReady, 50);
        ui.postDelayed(() -> {
            startWhenPrepared = true;
            tryStartVideo();
        }, (long) (INTRO_SECONDS * 1000));
    }

    /** Dissolve back into the photo. withSway: the photo keeps swaying 3 s before going still. */
    public void endJourney(boolean withSway, Runnable done) {
        outroDone = done;
        ui.removeCallbacksAndMessages(null);
        releasePlayer();
        renderer.beginOutro(withSway);
        ui.post(this::pollOutro);
    }

    /** Stop everything at once (app going to background). */
    public void stopNow() {
        ui.removeCallbacksAndMessages(null);
        releasePlayer();
        outroDone = null;
        renderer.setMode(M_IDLE);
        Journey.active = false;
    }

    private void pollOutro() {
        if (renderer.mode == M_DONE) {
            Journey.active = false;
            Runnable r = outroDone;
            outroDone = null;
            renderer.setMode(M_IDLE);
            if (r != null) r.run();
        } else if (renderer.mode == M_OUTRO) {
            ui.postDelayed(this::pollOutro, 50);
        }
    }

    // ------------------------------------------------------------ player

    private void createPlayerWhenReady() {
        if (player != null) return;
        SurfaceTexture st = renderer.surfaceTexture;
        if (st == null) {
            ui.postDelayed(this::createPlayerWhenReady, 100);
            return;
        }
        int id = getResources().getIdentifier("psy_forest", "raw", getContext().getPackageName());
        if (id == 0) return;
        try {
            MediaPlayer mp = new MediaPlayer();
            AssetFileDescriptor afd = getResources().openRawResourceFd(id);
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            mp.setSurface(new Surface(st));
            mp.setVolume(0f, 0f);
            mp.setLooping(false);
            mp.setOnPreparedListener(p -> {
                playerPrepared = true;
                renderer.videoAspect = p.getVideoWidth() / (float) Math.max(1, p.getVideoHeight());
                tryStartVideo();
            });
            mp.setOnCompletionListener(p -> {
                Listener l = listener;
                if (l != null) l.onVideoCompleted();
            });
            mp.setOnErrorListener((p, what, extra) -> true);
            player = mp;
            playerPrepared = false;
            mp.prepareAsync();
        } catch (Exception e) {
            player = null;
        }
    }

    private void tryStartVideo() {
        if (player != null && playerPrepared && startWhenPrepared) {
            startWhenPrepared = false;
            try {
                player.start();
                renderer.videoStarted = true;
            } catch (IllegalStateException e) {
                // ignore
            }
        }
    }

    private void releasePlayer() {
        startWhenPrepared = false;
        playerPrepared = false;
        renderer.videoStarted = false;
        if (player != null) {
            try {
                player.stop();
            } catch (IllegalStateException e) {
                // not started
            }
            player.release();
            player = null;
        }
    }

    // ------------------------------------------------------------ GL renderer

    private static final String VS =
            "attribute vec4 aPos;\n"
                    + "attribute vec2 aUV;\n"
                    + "varying vec2 vUV;\n"
                    + "void main(){ gl_Position = aPos; vUV = aUV; }\n";

    private static final String FS =
            "#extension GL_OES_EGL_image_external : require\n"
                    + "precision highp float;\n"
                    + "varying vec2 vUV;\n"
                    + "uniform samplerExternalOES uVideo;\n"
                    + "uniform sampler2D uPhoto;\n"
                    + "uniform mat4 uSTM;\n"
                    + "uniform float uTime;\n"
                    + "uniform float uI;\n"
                    + "uniform float uMix;\n"
                    + "uniform float uSway;\n"
                    + "uniform float uAsp;\n"
                    + "uniform vec2 uPhotoScale;\n"
                    + "uniform float uBand;\n"
                    + "uniform float uCW;\n"
                    + "uniform float uPanX;\n"
                    + "const vec3 W = vec3(0.299, 0.587, 0.114);\n"
                    + "\n"
                    + "vec3 photo(vec2 uv){\n"
                    + "  float z = 1.0 + 0.045 * uSway;\n"
                    + "  vec2 p = (uv - 0.5) / z + 0.5;\n"
                    + "  float top = 1.0 - uv.y;\n"
                    + "  float w = 0.4 + 0.6 * top * top;\n"
                    + "  p.x += uSway * 0.010 * (w * sin(uTime*0.9 + uv.y*3.0 + uv.x*2.0)\n"
                    + "        + 0.55 * sin(uTime*1.7 + uv.x*11.0 + uv.y*6.0)\n"
                    + "        + 0.25 * sin(uTime*3.1 + uv.x*23.0 - uv.y*17.0));\n"
                    + "  p.y += uSway * 0.004 * (sin(uTime*1.2 + uv.x*8.0 + uv.y*5.0)\n"
                    + "        + 0.5 * sin(uTime*2.3 + uv.x*17.0 - uv.y*9.0));\n"
                    + "  vec2 q = (p - 0.5) * uPhotoScale + 0.5;\n"
                    + "  q.y = 1.0 - q.y;\n"
                    + "  return texture2D(uPhoto, q).rgb;\n"
                    + "}\n"
                    + "\n"
                    + "float mirror1(float y){ y = mod(y, 2.0); return y > 1.0 ? 2.0 - y : y; }\n"
                    + "\n"
                    + "vec3 vsample(vec2 c){\n"
                    + "  c = clamp(c, vec2(0.003), vec2(0.997));\n"
                    + "  vec4 t = uSTM * vec4(c, 0.0, 1.0);\n"
                    + "  return texture2D(uVideo, t.xy).rgb;\n"
                    + "}\n"
                    + "\n"
                    + "vec3 hueRot(vec3 c, float a){\n"
                    + "  mat3 toYIQ = mat3(0.299, 0.596, 0.211, 0.587, -0.274, -0.523, 0.114, -0.322, 0.312);\n"
                    + "  mat3 toRGB = mat3(1.0, 1.0, 1.0, 0.956, -0.272, -1.106, 0.621, -0.647, 1.703);\n"
                    + "  vec3 q = toYIQ * c;\n"
                    + "  float cs = cos(a);\n"
                    + "  float sn = sin(a);\n"
                    + "  q.yz = vec2(q.y*cs - q.z*sn, q.y*sn + q.z*cs);\n"
                    + "  return toRGB * q;\n"
                    + "}\n"
                    + "\n"
                    + "vec3 video(vec2 uv){\n"
                    + "  float t = uTime;\n"
                    + "  float yb = (uv.y - 0.5) / uBand + 0.5 + 0.02 * sin(t * 0.13);\n"
                    + "  float y = mirror1(yb);\n"
                    + "  float x = (uv.x - 0.5) * uCW + uPanX;\n"
                    + "  float near = 1.0 - y;\n"
                    + "  vec2 c0 = vec2(x, y);\n"
                    + "  float lum = dot(vsample(c0), W);\n"
                    + "  vec2 off = uI * 0.014 * vec2(sin(t*0.7 + y*9.0 + lum*5.0), cos(t*0.6 + x*7.0 + lum*4.0)) * (0.35 + near);\n"
                    + "  off.x += uI * 0.011 * sin(y*22.0 + t*1.4) * (1.0 - lum);\n"
                    + "  off.y += uI * 0.006 * sin(x*30.0 + t*0.9);\n"
                    + "  float k = 1.0 + 0.9 * uI;\n"
                    + "  vec3 col;\n"
                    + "  col.r = vsample(c0 + off * k).r;\n"
                    + "  col.g = vsample(c0 + off).g;\n"
                    + "  col.b = vsample(c0 - off * k).b;\n"
                    + "  float l2 = dot(col, W);\n"
                    + "  float eX = 0.003;\n"
                    + "  float eY = 0.005;\n"
                    + "  float edge = abs(dot(vsample(c0 + off + vec2(eX, 0.0)), W) - dot(vsample(c0 + off - vec2(eX, 0.0)), W))\n"
                    + "             + abs(dot(vsample(c0 + off + vec2(0.0, eY)), W) - dot(vsample(c0 + off - vec2(0.0, eY)), W));\n"
                    + "  float a = uI * (1.6 * sin(t*0.27 + l2*3.0 + y*2.0) + 0.8 * sin(t*0.11 + x*4.0));\n"
                    + "  vec3 rot = hueRot(col, a);\n"
                    + "  float ll = dot(rot, W);\n"
                    + "  rot = mix(vec3(ll), rot, 1.0 + 0.8 * uI);\n"
                    + "  col = mix(col, rot, clamp(0.25 + 0.7 * uI, 0.0, 1.0));\n"
                    + "  col = (col - 0.5) * (1.0 + 0.25 * uI) + 0.5;\n"
                    + "  vec3 glow = 0.5 + 0.5 * cos(6.2831 * (vec3(0.0, 0.33, 0.67) + t * 0.05 + edge * 2.0));\n"
                    + "  col += glow * edge * 2.2 * uI;\n"
                    + "  vec2 gc = c0 + vec2(0.02 * sin(t*0.5), 0.015 * cos(t*0.4)) + off * 2.0;\n"
                    + "  vec3 g = hueRot(vsample(gc), a + 2.1);\n"
                    + "  col = 1.0 - (1.0 - clamp(col, 0.0, 1.0)) * (1.0 - g * 0.30 * uI);\n"
                    + "  for (int i = 0; i < 3; i++) {\n"
                    + "    float fi = float(i);\n"
                    + "    vec2 ctr = vec2(0.5 + 0.40 * sin(t*0.29 + fi*2.1), 0.5 + 0.40 * cos(t*0.23 + fi*1.7));\n"
                    + "    float d = length((uv - ctr) * vec2(1.0, uAsp));\n"
                    + "    vec3 hc = 0.5 + 0.5 * cos(vec3(0.0, 2.0, 4.0) + t*0.3 + fi*2.0);\n"
                    + "    col += hc * exp(-d*d*5.0) * 0.16 * uI;\n"
                    + "  }\n"
                    + "  vec2 vv = uv - 0.5;\n"
                    + "  col *= 1.0 - 0.45 * dot(vv, vv) * (0.8 + 0.2 * sin(t*0.5));\n"
                    + "  return clamp(col, 0.0, 1.0);\n"
                    + "}\n"
                    + "\n"
                    + "void main(){\n"
                    + "  vec3 c;\n"
                    + "  if (uMix < 0.001) c = photo(vUV);\n"
                    + "  else if (uMix > 0.999) c = video(vUV);\n"
                    + "  else c = mix(photo(vUV), video(vUV), uMix);\n"
                    + "  gl_FragColor = vec4(c, 1.0);\n"
                    + "}\n";

    private final class Renderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

        volatile int mode = M_IDLE;
        volatile boolean videoStarted = false;
        volatile float videoAspect = 720f / 406f;
        volatile SurfaceTexture surfaceTexture;

        private volatile long modeStartNs = 0L;
        private volatile boolean outroSway = false;
        private volatile float outroFromMix = 1f;
        private volatile float outroFromI = 0f;

        private final Object lock = new Object();
        private boolean frameAvailable = false;
        private boolean gotFrame = false;
        private long firstFrameNs = 0L;
        private float lastMix = 0f;
        private float lastI = 0f;

        private int program;
        private int videoTex, photoTex;
        private int aPos, aUV;
        private int uVideo, uPhoto, uSTM, uTime, uI, uMix, uSway, uAsp, uPhotoScale, uBand, uCW, uPanX;
        private final float[] stm = new float[16];
        private FloatBuffer quad;
        private float photoAspect = 736f / 1599f;
        private int vw = 1, vh = 1;
        private final long t0 = System.nanoTime();

        void setMode(int m) {
            modeStartNs = System.nanoTime();
            mode = m;
        }

        void beginIntro() {
            synchronized (lock) {
                gotFrame = false;
                frameAvailable = false;
                firstFrameNs = 0L;
            }
            lastMix = 0f;
            lastI = 0f;
            setMode(M_INTRO);
        }

        void beginOutro(boolean withSway) {
            outroSway = withSway;
            outroFromMix = lastMix;
            outroFromI = lastI;
            setMode(M_OUTRO);
        }

        @Override
        public void onFrameAvailable(SurfaceTexture st) {
            synchronized (lock) {
                frameAvailable = true;
            }
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            program = buildProgram(VS, FS);
            aPos = GLES20.glGetAttribLocation(program, "aPos");
            aUV = GLES20.glGetAttribLocation(program, "aUV");
            uVideo = GLES20.glGetUniformLocation(program, "uVideo");
            uPhoto = GLES20.glGetUniformLocation(program, "uPhoto");
            uSTM = GLES20.glGetUniformLocation(program, "uSTM");
            uTime = GLES20.glGetUniformLocation(program, "uTime");
            uI = GLES20.glGetUniformLocation(program, "uI");
            uMix = GLES20.glGetUniformLocation(program, "uMix");
            uSway = GLES20.glGetUniformLocation(program, "uSway");
            uAsp = GLES20.glGetUniformLocation(program, "uAsp");
            uPhotoScale = GLES20.glGetUniformLocation(program, "uPhotoScale");
            uBand = GLES20.glGetUniformLocation(program, "uBand");
            uCW = GLES20.glGetUniformLocation(program, "uCW");
            uPanX = GLES20.glGetUniformLocation(program, "uPanX");

            float[] v = {
                    -1f, -1f, 0f, 0f,
                    1f, -1f, 1f, 0f,
                    -1f, 1f, 0f, 1f,
                    1f, 1f, 1f, 1f};
            quad = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(v).position(0);

            int[] tex = new int[2];
            GLES20.glGenTextures(2, tex, 0);
            videoTex = tex[0];
            photoTex = tex[1];

            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTex);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            Bitmap bmp = BitmapFactory.decodeResource(getResources(), R.drawable.forest_bg);
            photoAspect = bmp.getWidth() / (float) bmp.getHeight();
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, photoTex);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
            bmp.recycle();

            SurfaceTexture st = new SurfaceTexture(videoTex);
            st.setOnFrameAvailableListener(this);
            surfaceTexture = st;
            android.opengl.Matrix.setIdentityM(stm, 0);
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            vw = Math.max(1, width);
            vh = Math.max(1, height);
            GLES20.glViewport(0, 0, vw, vh);
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            long now = System.nanoTime();
            float t = (now - t0) / 1e9f;
            int m = mode;

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (m == M_IDLE || m == M_DONE) {
                return;
            }

            synchronized (lock) {
                if (frameAvailable && surfaceTexture != null) {
                    try {
                        surfaceTexture.updateTexImage();
                        surfaceTexture.getTransformMatrix(stm);
                        gotFrame = true;
                    } catch (RuntimeException e) {
                        // surface not ready yet
                    }
                    frameAvailable = false;
                }
                if (gotFrame && videoStarted && firstFrameNs == 0L) {
                    firstFrameNs = now;
                    Journey.videoStartNs = now;
                    if (m == M_INTRO) {
                        setMode(M_VIDEO);
                        m = M_VIDEO;
                    }
                }
            }

            float mix = 0f, sway = 0f, intensity = 0f;
            if (m == M_INTRO) {
                float e = (now - modeStartNs) / 1e9f;
                sway = Journey.smooth(0f, 1f, e);
            } else if (m == M_VIDEO) {
                float vt = (now - firstFrameNs) / 1e9f;
                mix = Journey.smooth(0f, 4f, vt);
                sway = 1f - mix;
                intensity = Journey.intensity(vt);
            } else if (m == M_OUTRO) {
                float o = (now - modeStartNs) / 1e9f;
                float fade = Journey.smooth(0f, 1.2f, o);
                mix = outroFromMix * (1f - fade);
                intensity = outroFromI * (1f - fade);
                if (outroSway) {
                    float hold = 1.2f + 3f;
                    sway = o < hold ? 1f : 1f - Journey.smooth(hold, hold + 0.6f, o);
                    if (o >= hold + 0.6f) {
                        setMode(M_DONE);
                    }
                } else {
                    sway = 0f;
                    if (o >= 1.2f) {
                        setMode(M_DONE);
                    }
                }
            }
            lastMix = mix;
            lastI = intensity;

            // layout numbers
            float viewAR = vw / (float) vh;
            float sx = Math.min(1f, viewAR / photoAspect);
            float sy = Math.min(1f, photoAspect / viewAR);
            float bandPx = vw / (CROP_W * videoAspect);
            float bandFrac = bandPx / vh;
            float panX = 0.5f + (0.5f - CROP_W / 2f) * (float) Math.sin(t * 0.07f);

            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTex);
            GLES20.glUniform1i(uVideo, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, photoTex);
            GLES20.glUniform1i(uPhoto, 1);
            GLES20.glUniformMatrix4fv(uSTM, 1, false, stm, 0);
            GLES20.glUniform1f(uTime, t);
            GLES20.glUniform1f(uI, intensity);
            GLES20.glUniform1f(uMix, gotFrame ? mix : 0f);
            GLES20.glUniform1f(uSway, sway);
            GLES20.glUniform1f(uAsp, vh / (float) vw);
            GLES20.glUniform2f(uPhotoScale, sx, sy);
            GLES20.glUniform1f(uBand, bandFrac);
            GLES20.glUniform1f(uCW, CROP_W);
            GLES20.glUniform1f(uPanX, panX);

            quad.position(0);
            GLES20.glEnableVertexAttribArray(aPos);
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad);
            quad.position(2);
            GLES20.glEnableVertexAttribArray(aUV);
            GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 16, quad);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }

        private int buildProgram(String vs, String fs) {
            int v = compile(GLES20.GL_VERTEX_SHADER, vs);
            int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, v);
            GLES20.glAttachShader(p, f);
            GLES20.glLinkProgram(p);
            return p;
        }

        private int compile(int type, String src) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            return s;
        }
    }
}
