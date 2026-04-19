package com.jme3.lwjgl.test;

import com.jme3.app.LegacyApplication;
import com.jme3.asset.TextureKey;
import com.jme3.asset.plugins.ClasspathLocator;
import com.jme3.export.binary.BinaryImporter;
import com.jme3.input.FlyByCamera;
import com.jme3.material.Material;
import com.jme3.material.plugins.J3MLoader;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Box;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeSystem;
import com.jme3.system.VulkanSystemDelegate;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import javax.imageio.ImageIO;

public class VulkanMultiSetTextureTest extends LegacyApplication {

    //"Common/MatDefs/Aurora/Aurora.j3md"
    //"Common/MatDefs/Misc/VKUnshaded.j3md"
    private static final String MAT_DEF = "Common/MatDefs/Misc/VKUnshaded.j3md";

    private final Node rootNode = new Node("Root");
    private ViewPort viewPort;
    private Camera cam;
    private FlyByCamera flyCam;

    private float fpsTime = 0f;
    private int fpsFrames = 0;

    // 新增：保存测试几何体，update 时可拿到 material
    private Geometry testGeom;

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

        cam.setLocation(new Vector3f(0f, 0f, 20f));
        cam.setFrustumFar(1000f);

        flyCam = new FlyByCamera(cam);
        flyCam.setMoveSpeed(30f);
        flyCam.setDragToRotate(true);
        flyCam.registerWithInput(inputManager);

        Box box = new Box(2f, 2f, 2f);
        testGeom = new Geometry("MultiSetBox", box);

        Material mat = new Material(assetManager, MAT_DEF);
        //"F:/JME/jmonkeyengine/jme3-core/src/main/resources/Common/Textures/MissingMaterial.png"
        Texture2D extra = loadPngFromFileNoJmeLoader("C:\\Users\\Administrator\\Pictures\\sky.png", true);
        if (extra != null) {
            mat.setTexture("ColorMap", extra);
            //mat.setTexture("ExtraTex", extra);
        }
//        assetManager.registerLocator("F:/AYA2022年10月16日/Jme", com.jme3.asset.plugins.FileLocator.class);
//        Spatial model = assetManager.loadModel("kasolia.j3o");
//        model.setMaterial(mat);
//        rootNode.attachChild(model);
        testGeom.setMaterial(mat);
        rootNode.attachChild(testGeom);
       
        System.out.println("[App] mat.ExtraTex=" + mat.getParam("ExtraTex"));
        System.out.println("[App] init done. children=" + rootNode.getQuantity()
                + ", ExtraTex=" + (extra != null));
    }

    private Texture loadTexture(String path) {
        try {
            TextureKey key = new TextureKey(path, false);
            Texture tex = assetManager.loadTexture(key);
            tex.setAnisotropicFilter(4);
            return tex;
        } catch (Exception e) {
            System.err.println("[Warn] texture load failed: " + path + " -> " + e.getMessage());
            return null;
        }
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

    private static Texture2D loadPngFromFileNoJmeLoader(String absPath, boolean flipY) {
        try {
            BufferedImage bi = ImageIO.read(new File(absPath));
            if (bi == null) {
                return null;
            }

            int w = bi.getWidth();
            int h = bi.getHeight();
            ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);

            for (int row = 0; row < h; row++) {
                int y = flipY ? (h - 1 - row) : row; // 关键：按需翻转Y
                for (int x = 0; x < w; x++) {
                    int argb = bi.getRGB(x, y);
                    byte a = (byte) ((argb >> 24) & 0xFF);
                    byte r = (byte) ((argb >> 16) & 0xFF);
                    byte g = (byte) ((argb >> 8) & 0xFF);
                    byte b = (byte) (argb & 0xFF);
                    buf.put(r).put(g).put(b).put(a);
                }
            }
            buf.flip();

            Image img = new Image(Image.Format.RGBA8, w, h, buf, null, ColorSpace.Linear);
            Texture2D tex = new Texture2D(img);
            tex.setMinFilter(com.jme3.texture.Texture.MinFilter.BilinearNoMipMaps);
            tex.setMagFilter(com.jme3.texture.Texture.MagFilter.Bilinear);
            tex.setWrap(com.jme3.texture.Texture.WrapMode.Repeat);
            return tex;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static void main(String[] args) {
        JmeSystem.setSystemDelegate(new VulkanSystemDelegate());

        AppSettings s = new AppSettings(true);
        s.setCustomRenderer(com.jme3.renderer.vulkan.context.LwjglVulkanContext.class);
        s.setWidth(1920);
        s.setHeight(1080);
        s.setTitle("Vulkan MultiSet Texture Test");

        VulkanMultiSetTextureTest app = new VulkanMultiSetTextureTest();
        app.setSettings(s);
        app.start();
    }
}
