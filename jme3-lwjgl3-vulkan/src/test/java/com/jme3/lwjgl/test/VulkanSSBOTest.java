package com.jme3.lwjgl.test;

import com.jme3.app.LegacyApplication;
import com.jme3.asset.plugins.ClasspathLocator;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.vulkan.VKRenderer;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Box;
import com.jme3.shader.Shader;
import com.jme3.shader.bufferobject.BufferObject;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeSystem;
import com.jme3.system.VulkanSystemDelegate;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

public class VulkanSSBOTest extends LegacyApplication {

    private final Node rootNode = new Node("Root");
    private Geometry testGeom;
    private Shader computeShader;
    private Texture2D computeTex;
    private Object textureImage; 
    private BufferObject ssbo;

    @Override
    public void initialize() {
        super.initialize();
        assetManager.registerLocator("/", ClasspathLocator.class);

        Camera cam = getCamera();
        ViewPort viewPort = renderManager.createMainView("Main", cam);
        viewPort.setBackgroundColor(ColorRGBA.DarkGray);
        viewPort.attachScene(rootNode);

        cam.setLocation(new Vector3f(0f, 0f, 4f));
        cam.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);

        testGeom = new Geometry("SSBOBox", new Box(1f, 1f, 1f));

        computeTex = new Texture2D(512, 512, Image.Format.RGBA8);
        computeTex.getImage().setColorSpace(ColorSpace.Linear);
        computeTex.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
        computeTex.setMagFilter(Texture.MagFilter.Bilinear);

        try {
            Class<?> tiClass = Class.forName("com.jme3.texture.TextureImage");
            Object access = Class.forName("com.jme3.texture.TextureImage$Access").getEnumConstants()[2];
            textureImage = tiClass.getConstructor(Texture.class, int.class, int.class, access.getClass())
                    .newInstance(computeTex, 0, 0, access);
        } catch (Exception e) {
            try {
                textureImage = Class.forName("com.jme3.texture.TextureImage").getConstructor(Texture.class).newInstance(computeTex);
            } catch (Exception ex) { ex.printStackTrace(); }
        }

        // 1. 创建大型 SSBO 阵列 (4个 vec4，共 16 个 float = 64 bytes)
        ssbo = new BufferObject(); 
        ByteBuffer mySafeBuf = BufferUtils.createByteBuffer(16 * 4);
        ssbo.setData(mySafeBuf);
        ssbo.setUpdateNeeded(); 

        // 2. 构建 SSBO 专注型着色器 (读取阵列数据)
        computeShader = new Shader();
        String source = "#version 450\n"
                + "layout(local_size_x = 16, local_size_y = 16) in;\n"
                + "layout(set = 0, binding = 0, rgba8) uniform writeonly image2D uResultImage;\n"
                + "layout(set = 0, binding = 1, std430) buffer SSBO {\n"
                + "    vec4 colorArray[4]; // 测试：读取结构化阵列\n"
                + "} ssbo;\n"
                + "void main() {\n"
                + "    ivec2 tc = ivec2(gl_GlobalInvocationID.xy);\n"
                + "    ivec2 size = imageSize(uResultImage);\n"
                + "    if(tc.x >= size.x || tc.y >= size.y) return;\n"
                + "    \n"
                + "    // 将屏幕均分为 4 个垂直色带\n"
                + "    float uvX = float(tc.x) / float(size.x);\n"
                + "    int index = int(uvX * 4.0);\n"
                + "    if(index > 3) index = 3;\n"
                + "    \n"
                + "    // 直接从 SSBO 取出当前频段对应的颜色\n"
                + "    vec4 finalColor = ssbo.colorArray[index];\n"
                + "    imageStore(uResultImage, tc, finalColor);\n"
                + "}\n";

        computeShader.addSource(Shader.ShaderType.Compute, "SSBOArrayTest", source, "", "GLSL450");

        Material mat = new Material(assetManager, "VkCommon/MatDefs/Misc/VKUnshaded.j3md");
        mat.setTexture("ColorMap", computeTex);
        testGeom.setMaterial(mat);
        rootNode.attachChild(testGeom);
    }

    @Override
    public void update() {
        super.update();
        float safeTpf = timer.getTimePerFrame() <= 0.0001f ? 0.016f : timer.getTimePerFrame();

        // 极限压力测试：CPU 端每帧都向 SSBO 注入完全不同的随机颜色矩阵
        if (ssbo != null) {
            ByteBuffer buf = ssbo.getData();
            if (buf != null) {
                buf.clear();
                FloatBuffer fb = buf.asFloatBuffer();
                for (int i = 0; i < 4; i++) {
                    fb.put(i * 4 + 0, (float) Math.random()); // R
                    fb.put(i * 4 + 1, (float) Math.random()); // G
                    fb.put(i * 4 + 2, (float) Math.random()); // B
                    fb.put(i * 4 + 3, 1.0f);                  // A
                }
                ssbo.setUpdateNeeded(); // 依赖 HOST_COHERENT 直写机制
            }
        }

        testGeom.rotate(0.5f * safeTpf, 0.8f * safeTpf, 0.2f * safeTpf);
        rootNode.updateLogicalState(safeTpf);
        rootNode.updateGeometricState();

        if (context != null && context.isRenderable() && renderManager.getRenderer() instanceof VKRenderer) {
            VKRenderer vkRenderer = (VKRenderer) renderManager.getRenderer();
            if (ssbo != null) vkRenderer.updateShaderStorageBufferObjectData(ssbo);

            if (computeShader != null && textureImage != null) {
                vkRenderer.setShader(computeShader);
                try {
                    for(java.lang.reflect.Method m : vkRenderer.getClass().getMethods()){
                        if(m.getName().equals("setTextureImage") && m.getParameterCount() == 2) {
                            m.invoke(vkRenderer, 0, textureImage); break;
                        }
                    }
                    vkRenderer.setShaderStorageBufferObject(1, ssbo);
                    vkRenderer.getClass().getMethod("dispatchCompute", int.class, int.class, int.class)
                            .invoke(vkRenderer, 32, 32, 1);
                } catch (Exception e) {}
            }
            vkRenderer.setShader(null);
            renderManager.render(safeTpf, true);
        }
    }

    public static void main(String[] args) {
        JmeSystem.setSystemDelegate(new VulkanSystemDelegate());
        AppSettings s = new AppSettings(true);
        s.setCustomRenderer(com.jme3.renderer.vulkan.context.LwjglVulkanContext.class);
        s.setWidth(1280); s.setHeight(720); s.setTitle("2. SSBO Data Array Stress Test");
        VulkanSSBOTest app = new VulkanSSBOTest();
        app.setSettings(s); app.start();
    }
}