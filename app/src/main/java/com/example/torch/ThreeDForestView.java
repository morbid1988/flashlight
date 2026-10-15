package com.example.torch;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Random;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Procedural 3D forest: camera travel, trees, moss, stones and luminous mushrooms. */
public final class ThreeDForestView extends GLSurfaceView {
    private final ForestRenderer forestRenderer;

    public ThreeDForestView(Context context) {
        super(context);
        setEGLContextClientVersion(2);
        setPreserveEGLContextOnPause(true);
        forestRenderer = new ForestRenderer();
        setRenderer(forestRenderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        setClickable(false);
        setFocusable(false);
    }

    public void startJourney() {
        forestRenderer.start();
        setRenderMode(RENDERMODE_CONTINUOUSLY);
    }

    public void stopJourney() {
        forestRenderer.stop();
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        requestRender();
    }
    
public void returnToStart() {
    forestRenderer.returnToStart();
    setRenderMode(RENDERMODE_CONTINUOUSLY);
}
    private static final class ForestRenderer implements GLSurfaceView.Renderer {
        private static final String VERTEX =
            "uniform mat4 uMVP; attribute vec3 aPos; attribute vec3 aCol; " +
            "varying vec3 vCol; varying vec3 vPos; " +
            "void main(){vCol=aCol;vPos=aPos;gl_Position=uMVP*vec4(aPos,1.0);}";
        private static final String FRAGMENT =
            "precision mediump float; uniform float uTime; varying vec3 vCol; varying vec3 vPos; " +
            "void main(){float p=.5+.5*sin(uTime*.65+vPos.x*.8+vPos.z*.24);" +
            "vec3 shifted=vCol.gbr;vec3 c=mix(vCol,shifted,.18+.24*p);" +
            "c*=1.0+.12*sin(uTime*.8+vPos.y*1.6);gl_FragColor=vec4(min(c,vec3(1.0)),1.0);}";

        private final float[] projection = new float[16];
        private final float[] view = new float[16];
        private final float[] vp = new float[16];
        private final float[] mvp = new float[16];
        private FloatBuffer vertices;
        private int count, program, aPos, aCol, uMvp, uTime;
        private volatile boolean active;
        private volatile long startNs;
        private volatile boolean returning;
private volatile long returnNs;
private volatile float returnFromProgress;
        private int width = 1, height = 1;

        void start() {
    startNs = System.nanoTime();
    active = true;
    returning = false;
}

void stop() {
    active = false;
    returning = false;
}

void returnToStart() {
    if (!active || returning) return;

    long now = System.nanoTime();
    float elapsed = (now - startNs) / 1.0e9f;
    returnFromProgress = Math.min(1f, elapsed / 290f);
    returnNs = now;
    returning = true;
}
        @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            GLES20.glClearColor(.012f, .022f, .018f, 1f);
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            GLES20.glDisable(GLES20.GL_CULL_FACE);
            program = link(VERTEX, FRAGMENT);
            aPos = GLES20.glGetAttribLocation(program, "aPos");
            aCol = GLES20.glGetAttribLocation(program, "aCol");
            uMvp = GLES20.glGetUniformLocation(program, "uMVP");
            uTime = GLES20.glGetUniformLocation(program, "uTime");
            buildScene();
        }

        @Override public void onSurfaceChanged(GL10 gl, int w, int h) {
            width = Math.max(1, w); height = Math.max(1, h);
            GLES20.glViewport(0, 0, width, height);
            Matrix.perspectiveM(projection, 0, 66f, width/(float)height, .08f, 130f);
        }

        
@Override
public void onDrawFrame(GL10 gl) {
    GLES20.glClear(
            GLES20.GL_COLOR_BUFFER_BIT |
            GLES20.GL_DEPTH_BUFFER_BIT
    );

    if (vertices == null || program == 0) return;

    long now = System.nanoTime();
    float t = active ? (now - startNs) / 1.0e9f : 0f;

    float progress;
    float wobbleTime = 0f;

    if (returning) {
        float returnElapsed = (now - returnNs) / 1.0e9f;

        if (returnElapsed < 3f) {
            // برگشت سریع، مثل پخش معکوس فیلم
            progress = returnFromProgress
                    * (1f - Math.max(0f, returnElapsed / 3f));
        } else {
            progress = 0f;
            wobbleTime = returnElapsed - 3f;

            if (returnElapsed >= 6f) {
                returning = false;
                active = false;
            }
        }
    } else if (active) {
        progress = Math.min(1f, t / 290f);

        if (t >= 290f) {
            progress = 1f;
            wobbleTime = Math.min(3f, t - 290f);
        }
    } else {
        progress = 0f;
    }

    float theta = progress * (float) (Math.PI * 2.0);

    // مسیر حلقه‌ای؛ نقطه شروع و پایان یکسان است
    float camX = 8f * (float) Math.sin(theta);
    float camZ = 7.2f
            - 25.5f * (1f - (float) Math.cos(theta));
    float camY = 3.25f
            - 1.55f * (float) Math.sin(Math.PI * progress);

    float lookX = camX
            + ((progress < 0.001f || progress > 0.999f)
            ? 0.12f
            : (float) Math.sin(theta) * 0.7f);

    float lookY = 2.4f
            - 1.2f * (float) Math.sin(Math.PI * progress);
    float lookZ = camZ - 6f * (float) Math.cos(theta);

    // نوسان ملایم دوربین در پایان
    if (wobbleTime > 0f && wobbleTime < 3f) {
        camX += (float) Math.sin(wobbleTime * 2.7f) * 0.045f;
        camY += (float) Math.sin(wobbleTime * 2.1f) * 0.035f;
        lookX += (float) Math.sin(wobbleTime * 2.4f) * 0.025f;
    }

    Matrix.setLookAtM(
            view, 0,
            camX, camY, camZ,
            lookX, lookY, lookZ,
            0f, 1f, 0f
    );

    Matrix.multiplyMM(vp, 0, projection, 0, view, 0);

    GLES20.glUseProgram(program);
    GLES20.glUniformMatrix4fv(uMvp, 1, false, vp, 0);
    GLES20.glUniform1f(uTime, t);

    vertices.position(0);

    GLES20.glEnableVertexAttribArray(aPos);
    GLES20.glEnableVertexAttribArray(aCol);

    GLES20.glVertexAttribPointer(
            aPos, 3, GLES20.GL_FLOAT, false, 24, vertices
    );

    vertices.position(3);

    GLES20.glVertexAttribPointer(
            aCol, 3, GLES20.GL_FLOAT, false, 24, vertices
    );

    GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count);

    GLES20.glDisableVertexAttribArray(aPos);
    GLES20.glDisableVertexAttribArray(aCol);
}
        

        private void buildScene() {
            Mesh m = new Mesh();
            m.quad(-25,0,12,25,0,12,25,0,-62,-25,0,-62,.075f,.14f,.085f);
            m.quad(-1.55f,.02f,12,1.55f,.02f,12,1.1f,.02f,-62,-1.1f,.02f,-62,.20f,.17f,.105f);
            // The first close trunk establishes the opening point of view.
            m.cylinder(.48f,0,2.3f,.52f,8.6f,.42f,.23f,.12f,10);
            m.cone(.48f,6.1f,2.3f,2.2f,4.2f,0,0,0,.10f,.24f,.12f,9);
            Random random = new Random(71429);
            for (int i=0;i<100;i++) {
                float z=8f-random.nextFloat()*67f;
                float x=(random.nextBoolean()?1:-1)*(2.0f+random.nextFloat()*13f);
                float h=4.5f+random.nextFloat()*6.8f, rad=.25f+random.nextFloat()*.42f;
                m.cylinder(x,0,z,.20f+random.nextFloat()*.17f,h,.30f,.17f,.09f,7);
                float g=.16f+random.nextFloat()*.18f;
                m.cone(x,h*.45f,z,rad*2.5f,h*.58f,0,0,0,.075f,g,.12f,8);
                m.cone(x,h*.64f,z,rad*2.0f,h*.48f,0,0,0,.08f,g+.05f,.15f,8);
                m.cone(x,h*.80f,z,rad*1.45f,h*.38f,0,0,0,.12f,g+.09f,.17f,8);
            }
            // Ferns, ground plants, mossy rocks and fallen wood.
            for (int i=0;i<175;i++) {
                float z=7f-random.nextFloat()*62f;
                float x=(random.nextFloat()*2f-1f)*(2.3f+random.nextFloat()*8f);
                if (Math.abs(x)<1.6f) x += x<0?-1.25f:1.25f;
                float s=.12f+random.nextFloat()*.34f;
                if (i%4==0) m.sphere(x,s*.4f,z,s*1.2f,s*.65f,s,.15f,.23f,.12f,7,5);
                else {
                    m.cone(x,0,z,s*.45f,s,0,0,0,.10f,.22f,.09f,6);
                    m.cone(x+s*.22f,s*.22f,z,s*.55f,s*.45f,0,0,0,.13f,.28f,.12f,5);
                }
            }
            for (int i=0;i<78;i++) {
                float z=6f-random.nextFloat()*59f;
                float x=(random.nextFloat()*2f-1f)*(1.8f+random.nextFloat()*5.8f);
                float s=.12f+random.nextFloat()*.22f;
                float[][] caps={{.92f,.19f,.42f},{.98f,.48f,.16f},{.48f,.25f,.96f},{.98f,.75f,.28f},{.22f,.88f,.68f}};
                float[] c=caps[i%caps.length];
                m.cylinder(x,.02f,z,s*.12f,s*.78f,.75f,.66f,.47f,6);
                m.sphere(x,s*.82f,z,s*.82f,s*.43f,s*.82f,c[0],c[1],c[2],8,5);
                m.sphere(x,s*.56f,z,s*.20f,s*.18f,s*.20f,Math.min(1,c[0]+.08f),Math.min(1,c[1]+.08f),Math.min(1,c[2]+.08f),6,4);
            }
            for (int i=0;i<32;i++) {
                float z=5f-random.nextFloat()*56f, x=(random.nextFloat()*2f-1f)*8f;
                m.cylinder(x,.055f,z,.08f,.7f+random.nextFloat()*1.4f,.25f,.13f,.065f,5);
            }
            vertices=ByteBuffer.allocateDirect(m.n*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            vertices.put(m.data,0,m.n).position(0); count=m.n/6;
        }

        private static float smooth(float x) { x=Math.max(0,Math.min(1,x)); return x*x*(3-2*x); }
        private static int compile(int type,String source) {
            int s=GLES20.glCreateShader(type); GLES20.glShaderSource(s,source); GLES20.glCompileShader(s);
            int[] ok=new int[1]; GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0);
            if(ok[0]==0) throw new RuntimeException("Shader compile failed: "+GLES20.glGetShaderInfoLog(s));
            return s;
        }
        private static int link(String vs,String fs) {
            int v=compile(GLES20.GL_VERTEX_SHADER,vs), f=compile(GLES20.GL_FRAGMENT_SHADER,fs);
            int p=GLES20.glCreateProgram(); GLES20.glAttachShader(p,v); GLES20.glAttachShader(p,f); GLES20.glLinkProgram(p);
            int[] ok=new int[1]; GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0);
            if(ok[0]==0) throw new RuntimeException("Shader link failed: "+GLES20.glGetProgramInfoLog(p));
            GLES20.glDeleteShader(v); GLES20.glDeleteShader(f); return p;
        }

        private static final class Mesh {
            float[] data=new float[240000]; int n;
            void ensure(int size){if(n+size>data.length){float[] b=new float[data.length*2];System.arraycopy(data,0,b,0,n);data=b;}}
            void v(float x,float y,float z,float r,float g,float b){ensure(6);data[n++]=x;data[n++]=y;data[n++]=z;data[n++]=r;data[n++]=g;data[n++]=b;}
            void tri(float[] a,float[] b,float[] c,float r,float g,float bl){v(a[0],a[1],a[2],r,g,bl);v(b[0],b[1],b[2],r,g,bl);v(c[0],c[1],c[2],r,g,bl);}
            void quad(float ax,float ay,float az,float bx,float by,float bz,float cx,float cy,float cz,float dx,float dy,float dz,float r,float g,float b){tri(new float[]{ax,ay,az},new float[]{bx,by,bz},new float[]{cx,cy,cz},r,g,b);tri(new float[]{ax,ay,az},new float[]{cx,cy,cz},new float[]{dx,dy,dz},r,g,b);}
            void cylinder(float x,float y,float z,float rad,float h,float r,float g,float b,int sides){for(int i=0;i<sides;i++){double a=i*Math.PI*2/sides,q=(i+1)*Math.PI*2/sides;float[] p={x+(float)Math.cos(a)*rad,y,z+(float)Math.sin(a)*rad},t={x+(float)Math.cos(q)*rad,y,z+(float)Math.sin(q)*rad},u={t[0],y+h,t[2]},w={p[0],y+h,p[2]};tri(p,t,u,r*.88f,g*.88f,b*.88f);tri(p,u,w,r,g,b);}}
            void cone(float x,float y,float z,float rad,float h,float ox,float oy,float oz,float r,float g,float b,int sides){float[] tip={x+ox,y+h+oy,z+oz};for(int i=0;i<sides;i++){double a=i*Math.PI*2/sides,q=(i+1)*Math.PI*2/sides;float[] p={x+(float)Math.cos(a)*rad,y,z+(float)Math.sin(a)*rad},t={x+(float)Math.cos(q)*rad,y,z+(float)Math.sin(q)*rad};float shade=(i%2==0)?1f:.82f;tri(p,t,tip,r*shade,g*shade,b*shade);}}
            void sphere(float x,float y,float z,float rx,float ry,float rz,float r,float g,float b,int slices,int stacks){for(int j=0;j<stacks;j++){double v0=-Math.PI/2+j*Math.PI/stacks,v1=-Math.PI/2+(j+1)*Math.PI/stacks;for(int i=0;i<slices;i++){double u0=i*Math.PI*2/slices,u1=(i+1)*Math.PI*2/slices;float[] a=point(x,y,z,rx,ry,rz,u0,v0),bb=point(x,y,z,rx,ry,rz,u1,v0),c=point(x,y,z,rx,ry,rz,u1,v1),d=point(x,y,z,rx,ry,rz,u0,v1);tri(a,bb,c,r,g,b);tri(a,c,d,r,g,b);}}}
            float[] point(float x,float y,float z,float rx,float ry,float rz,double u,double v){return new float[]{x+rx*(float)(Math.cos(v)*Math.cos(u)),y+ry*(float)Math.sin(v),z+rz*(float)(Math.cos(v)*Math.sin(u))};}
        }
    }
}
