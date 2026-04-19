package com.jme3.lwjgl.test;

import com.jme3.app.LegacyApplication;
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
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;

import java.nio.ByteBuffer;

public class VulkanMultiSetTextureTest1 extends LegacyApplication {

    private static final String MAT_DEF = "Common/MatDefs/Aurora/Aurora.j3md";

    private final Node rootNode = new Node("Root");
    private ViewPort viewPort;
    private Camera cam;
    private FlyByCamera flyCam;

    private float fpsTime = 0f;
    private int fpsFrames = 0;

    @Override
    public void initialize() {
        super.initialize();

        assetManager.registerLoader(J3MLoader.class, "j3md", "j3m");

        cam = getCamera();
        viewPort = renderManager.createMainView("Main", cam);
        viewPort.setBackgroundColor(ColorRGBA.DarkGray);
        viewPort.attachScene(rootNode);

        cam.setLocation(new Vector3f(0f, 0f, 20f));
        cam.setFrustumFar(1000f);

        flyCam = new FlyByCamera(cam);
        flyCam.setMoveSpeed(30f);
        flyCam.setDragToRotate(true);
        flyCam.registerWithInput(inputManager);

        Box box = new Box(2f, 2f, 2f);
        Geometry geom = new Geometry("MultiSetBox", box);

        Material mat = new Material(assetManager, MAT_DEF);

        // 不依赖外部图片，直接构造两张纹理
        Texture colorMap = buildCheckerTex(
                (byte) 255, (byte) 80, (byte) 80, (byte) 255,   // A
                (byte) 80, (byte) 255, (byte) 80, (byte) 255    // B
        );

        Texture extraMap = buildCheckerTex(
                (byte) 80, (byte) 80, (byte) 255, (byte) 255,   // A
                (byte) 255, (byte) 255, (byte) 0, (byte) 255    // B
        );


        mat.setTexture("ExtraTex", extraMap);


        // 可选：确保 shader 颜色不是全白干扰判断
        // mat.setColor("Color", ColorRGBA.White);

        geom.setMaterial(mat);
        rootNode.attachChild(geom);

        //boolean hasColorMap = mat.getParam("ColorMap") != null && mat.getParam("ColorMap").getValue() != null;
        boolean hasExtra = mat.getMaterialDef().getMaterialParam("ExtraTex") != null
        && mat.getParam("ExtraTex") != null
        && mat.getParam("ExtraTex").getValue() != null;

        System.out.println("[App] init done. children=" + rootNode.getQuantity()
                + ", ExtraMap=" + hasExtra
                + ", ColorTex=" + texInfo(colorMap)
                + ", ExtraTex=" + texInfo(extraMap));
    }

    private static String texInfo(Texture t) {
        if (t == null || t.getImage() == null) return "null";
        return t.getImage().getFormat() + " " + t.getImage().getWidth() + "x" + t.getImage().getHeight();
    }

    /**
     * 2x2 棋盘纹理，RGBA8
     */
    private static Texture2D buildCheckerTex(
            byte ar, byte ag, byte ab, byte aa,
            byte br, byte bg, byte bb, byte ba) {

        ByteBuffer data = BufferUtils.createByteBuffer(2 * 2 * 4);

        // row0: A B
        data.put(ar).put(ag).put(ab).put(aa);
        data.put(br).put(bg).put(bb).put(ba);

        // row1: B A
        data.put(br).put(bg).put(bb).put(ba);
        data.put(ar).put(ag).put(ab).put(aa);

        data.flip();

        Image img = new Image(Image.Format.RGBA8, 2, 2, data, null, ColorSpace.Linear);
        Texture2D tex = new Texture2D(img);

        tex.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
        tex.setMagFilter(Texture.MagFilter.Nearest);
        tex.setWrap(Texture.WrapMode.Repeat);
        tex.setAnisotropicFilter(1);

        return tex;
    }

    @Override
    public void update() {
        super.update();

        float tpf = timer.getTimePerFrame();

        fpsTime += tpf;
        fpsFrames++;
        if (fpsTime >= 1.0f) {
            int fps = Math.round(fpsFrames / fpsTime);
            fpsTime = 0f;
            fpsFrames = 0;
            if (context != null) {
                context.setTitle("Vulkan MultiSet Texture Test | FPS: " + fps);
            }
        }

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
        s.setTitle("Vulkan MultiSet Texture Test");

        VulkanMultiSetTextureTest1 app = new VulkanMultiSetTextureTest1();
        app.setSettings(s);
        app.start();
    }
}
