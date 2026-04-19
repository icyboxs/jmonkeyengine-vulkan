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
import com.jme3.scene.Node;
import com.jme3.scene.shape.Box;
import com.jme3.shader.bufferobject.BufferObject;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeSystem;
import com.jme3.system.VulkanSystemDelegate;
import com.jme3.texture.Image;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;

public class VulkanDynamicResourceScaleTest extends LegacyApplication {

    private static final String MAT_DEF = "Common/MatDefs/Misc/VKUnshaded.j3md";

    private final Node rootNode = new Node("Root");
    private ViewPort viewPort;
    private Camera cam;
    private FlyByCamera flyCam;

    private Geometry geom;
    private Material mat;
    private Texture2D tex;

    private Renderer lowLevelRenderer;

    private float elapsed = 0f;
    private int stage = 0;

    private float fpsTime = 0f;
    private int fpsFrames = 0;

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

        geom = new Geometry("Box", new Box(2f, 2f, 2f));
        mat = new Material(assetManager, MAT_DEF);

        tex = createSolidTex(256, 256, (byte) 255, (byte) 0, (byte) 0, (byte) 255); // red
        mat.setTexture("ColorMap", tex);

        geom.setMaterial(mat);
        rootNode.attachChild(geom);

        lowLevelRenderer = extractRenderer();
        System.out.println("[Test] renderer=" + (lowLevelRenderer != null ? lowLevelRenderer.getClass().getName() : "null"));
        System.out.println("[Test] init done");
    }

    @Override
    public void update() {
        super.update();

        float tpf = timer.getTimePerFrame();
        elapsed += tpf;

        // 前端动画：旋转 + 循环缩放（不触发VB重建）
        geom.rotate(0f, tpf * 0.6f, 0f);
        float scale = 1.0f + 0.35f * FastMath.sin(elapsed * 2.0f); // 0.65 ~ 1.35
        geom.setLocalScale(scale);

        fpsTime += tpf;
        fpsFrames++;
        if (fpsTime >= 1.0f) {
            int fps = Math.round(fpsFrames / fpsTime);
            fpsTime = 0f;
            fpsFrames = 0;
            if (context != null) {
                context.setTitle("Vulkan Dynamic Resource + Frontend Scale Test | FPS: " + fps + " | stage=" + stage);
            }
        }

        // 仍保留你要测的3个接口
        if (lowLevelRenderer != null) {
            if (stage == 0 && elapsed > 2f) {
                System.out.println("[Test] stage0 -> modifyTexture to GREEN image");
                lowLevelRenderer.modifyTexture(tex, createSolidImage(256, 256, (byte) 0, (byte) 255, (byte) 0, (byte) 255), 0, 0);
                stage = 1;
            } else if (stage == 1 && elapsed > 4f) {
                System.out.println("[Test] stage1 -> modifyTexture to BLUE image");
                lowLevelRenderer.modifyTexture(tex, createSolidImage(256, 256, (byte) 0, (byte) 0, (byte) 255, (byte) 255), 0, 0);
                stage = 2;
            } else if (stage == 2 && elapsed > 6f) {
                System.out.println("[Test] stage2 -> deleteImage(current tex image)");
                lowLevelRenderer.deleteImage(tex.getImage());
                stage = 3;
            } else if (stage == 3 && elapsed > 8f) {
                System.out.println("[Test] stage3 -> modifyTexture to YELLOW after deleteImage");
                lowLevelRenderer.modifyTexture(tex, createSolidImage(256, 256, (byte) 255, (byte) 255, (byte) 0, (byte) 255), 0, 0);
                stage = 4;
            } else if (stage == 4 && elapsed > 10f) {
                System.out.println("[Test] stage4 -> deleteBuffer(BufferObject) no-op path");
                lowLevelRenderer.deleteBuffer((BufferObject) null);
                stage = 5;
            }
        }

        rootNode.updateLogicalState(tpf);
        rootNode.updateGeometricState();

        if (context != null && context.isRenderable()) {
            renderManager.render(tpf, true);
        }
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

    private static Texture2D createSolidTex(int w, int h, byte r, byte g, byte b, byte a) {
        return new Texture2D(createSolidImage(w, h, r, g, b, a));
    }

    private static Image createSolidImage(int w, int h, byte r, byte g, byte b, byte a) {
        ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
        for (int i = 0; i < w * h; i++) {
            buf.put(r).put(g).put(b).put(a);
        }
        buf.flip();
        return new Image(Image.Format.RGBA8, w, h, buf, null, ColorSpace.Linear);
    }

    public static void main(String[] args) {
        JmeSystem.setSystemDelegate(new VulkanSystemDelegate());

        AppSettings s = new AppSettings(true);
        s.setCustomRenderer(com.jme3.renderer.vulkan.context.LwjglVulkanContext.class);
        s.setWidth(1280);
        s.setHeight(720);
        s.setTitle("Vulkan Dynamic Resource + Frontend Scale Test");

        VulkanDynamicResourceScaleTest app = new VulkanDynamicResourceScaleTest();
        app.setSettings(s);
        app.start();
    }
}
