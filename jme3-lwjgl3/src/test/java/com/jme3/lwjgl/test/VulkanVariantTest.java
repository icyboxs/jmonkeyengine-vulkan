package com.jme3.lwjgl.test;

import com.jme3.app.LegacyApplication;
import com.jme3.asset.plugins.ClasspathLocator;
import com.jme3.input.FlyByCamera;
import com.jme3.material.Material;
import com.jme3.material.plugins.J3MLoader;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Box;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeSystem;
import com.jme3.system.VulkanSystemDelegate;

public class VulkanVariantTest extends LegacyApplication {

    private static final String MAT_DEF = "Common/MatDefs/Aurora/Aurora.j3md";

    private final Node rootNode = new Node("Root");
    private ViewPort viewPort;
    private Camera cam;
    private FlyByCamera flyCam;

    // ===== FPS 标题统计 =====
    private float fpsTime = 0f;
    private int fpsFrames = 0;
    private int fpsValue = 0;
    // =======================

    @Override
    public void initialize() {
        super.initialize();

        assetManager.registerLocator("/", ClasspathLocator.class);
        assetManager.registerLoader(J3MLoader.class, "j3md", "j3m");

        cam = getCamera();
        viewPort = renderManager.createMainView("Main", cam);
        viewPort.attachScene(rootNode);

        cam.setLocation(new Vector3f(0f, 0f, 20f));
        cam.setFrustumFar(1000f);

        flyCam = new FlyByCamera(cam);
        flyCam.setMoveSpeed(30f);
        flyCam.setDragToRotate(true);
        flyCam.registerWithInput(inputManager);

        Box box = new Box(2f, 2f, 2f);
        Geometry geom = new Geometry("SingleBox", box);

        Material mat = new Material(assetManager, MAT_DEF);
        geom.setMaterial(mat);
        geom.setLocalTranslation(0f, 0f, 0f);

        rootNode.attachChild(geom);

        System.out.println("[App] init done. children=" + rootNode.getQuantity());
    }

    @Override
    public void update() {
        super.update();

        float tpf = timer.getTimePerFrame();

        // ===== 统计 FPS 并更新标题 =====
        fpsTime += tpf;
        fpsFrames++;
        if (fpsTime >= 1.0f) {
            fpsValue = Math.round(fpsFrames / fpsTime);
            fpsTime = 0f;
            fpsFrames = 0;

            if (context != null) {
                context.setTitle("Vulkan Single Box | FPS: " + fpsValue);
            }
        }
        // ==============================

        rootNode.updateLogicalState(tpf);
        rootNode.updateGeometricState();

        if (context != null && context.isRenderable()) {
            renderManager.render(tpf, true);
        }
    }

    public static void main(String[] args) {
        JmeSystem.setSystemDelegate(new VulkanSystemDelegate());

        AppSettings s = new AppSettings(true);
        s.setCustomRenderer(com.jme3.renderer.vulkan.context.LwjglVulkanContext.class);
        s.setWidth(1920);
        s.setHeight(1080);
        s.setTitle("Vulkan Single Box");

        VulkanVariantTest app = new VulkanVariantTest();
        app.setSettings(s);
        app.start();
    }
}
