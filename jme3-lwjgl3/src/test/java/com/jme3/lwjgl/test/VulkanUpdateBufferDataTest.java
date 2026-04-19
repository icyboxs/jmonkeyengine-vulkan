package com.jme3.lwjgl.test;

import com.jme3.app.LegacyApplication;
import com.jme3.asset.plugins.ClasspathLocator;
import com.jme3.export.binary.BinaryImporter;
import com.jme3.input.FlyByCamera;
import com.jme3.material.Material;
import com.jme3.material.plugins.J3MLoader;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.shape.Box;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeSystem;
import com.jme3.system.VulkanSystemDelegate;

import java.lang.reflect.Field;
import java.nio.FloatBuffer;

public class VulkanUpdateBufferDataTest extends LegacyApplication {

    private static final String MAT_DEF = "Common/MatDefs/Misc/VKUnshaded.j3md";

    private final Node rootNode = new Node("Root");
    private ViewPort viewPort;
    private Camera cam;
    private FlyByCamera flyCam;

    private Geometry geom;
    private Mesh mesh;
    private VertexBuffer posVb;
    private float[] basePos;

    private Renderer lowLevelRenderer;

    private float elapsed = 0f;
    private float deformAccum = 0f;
    private int updateCalls = 0;

    @Override
    public void initialize() {
        super.initialize();

        assetManager.registerLocator("/", ClasspathLocator.class);
        assetManager.registerLoader(J3MLoader.class, "j3md", "j3m");
        assetManager.registerLoader(BinaryImporter.class, "j3o");

        cam = getCamera();
        viewPort = renderManager.createMainView("Main", cam);
        viewPort.setBackgroundColor(ColorRGBA.DarkGray);
        viewPort.attachScene(rootNode);

        cam.setLocation(new Vector3f(0f, 0f, 10f));
        cam.setFrustumFar(1000f);

        flyCam = new FlyByCamera(cam);
        flyCam.setMoveSpeed(20f);
        flyCam.setDragToRotate(true);
        flyCam.registerWithInput(inputManager);

        mesh = new Box(2f, 2f, 2f);
        geom = new Geometry("Box", mesh);
        geom.setMaterial(new Material(assetManager, MAT_DEF));
        rootNode.attachChild(geom);

        posVb = mesh.getBuffer(VertexBuffer.Type.Position);
        basePos = snapshotPositions(posVb);

        lowLevelRenderer = extractRenderer();
        System.out.println("[Test] renderer=" + (lowLevelRenderer != null ? lowLevelRenderer.getClass().getName() : "null"));
        System.out.println("[Test] init done");
    }

    @Override
    public void update() {
        super.update();

        float tpf = timer.getTimePerFrame();
        elapsed += tpf;
        deformAccum += tpf;

        geom.rotate(0f, tpf * 0.6f, 0f);

        // 低频触发 updateBufferData，避免每帧重建导致过载
        if (lowLevelRenderer != null && deformAccum >= 0.25f) {
            deformAccum = 0f;
            applyWaveDeform(elapsed);
            lowLevelRenderer.updateBufferData(posVb);
            updateCalls++;
            System.out.println("[Test] updateBufferData call #" + updateCalls);
        }

        if (context != null) {
            context.setTitle("Vulkan updateBufferData Test | calls=" + updateCalls);
        }

        rootNode.updateLogicalState(tpf);
        rootNode.updateGeometricState();

        if (context != null && context.isRenderable()) {
            renderManager.render(tpf, true);
        }
    }

    private void applyWaveDeform(float time) {
        FloatBuffer fb = (FloatBuffer) posVb.getData();
        fb.rewind();

        int vCount = basePos.length / 3;
        float amp = 0.2f;
        float freq = 1.3f;

        for (int i = 0; i < vCount; i++) {
            float x = basePos[i * 3];
            float y = basePos[i * 3 + 1];
            float z = basePos[i * 3 + 2];

            float dz = FastMath.sin(time * 2.0f + x * freq + y * 0.5f) * amp;
            fb.put(x).put(y).put(z + dz);
        }

        fb.flip();
        posVb.updateData(fb);
        mesh.updateBound();
    }

    private static float[] snapshotPositions(VertexBuffer positionVb) {
        FloatBuffer fb = (FloatBuffer) positionVb.getData();
        FloatBuffer dup = fb.duplicate();
        dup.rewind();
        float[] arr = new float[dup.remaining()];
        dup.get(arr);
        return arr;
    }

    private Renderer extractRenderer() {
        try {
            Field f = renderManager.getClass().getDeclaredField("renderer");
            f.setAccessible(true);
            return (Renderer) f.get(renderManager);
        } catch (Throwable t) {
            System.err.println("[Test] extractRenderer failed: " + t.getMessage());
            return null;
        }
    }

    public static void main(String[] args) {
        JmeSystem.setSystemDelegate(new VulkanSystemDelegate());

        AppSettings s = new AppSettings(true);
        s.setCustomRenderer(com.jme3.renderer.vulkan.context.LwjglVulkanContext.class);
        s.setWidth(1280);
        s.setHeight(720);
        s.setTitle("Vulkan updateBufferData Test");

        VulkanUpdateBufferDataTest app = new VulkanUpdateBufferDataTest();
        app.setSettings(s);
        app.start();
    }
}
