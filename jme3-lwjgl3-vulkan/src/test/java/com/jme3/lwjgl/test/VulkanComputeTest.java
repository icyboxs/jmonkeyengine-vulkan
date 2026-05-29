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

public class VulkanComputeTest extends LegacyApplication {

    private final Node rootNode = new Node("Root");
    private Geometry testGeom;

    private Shader computeShader;
    private Texture2D computeTex;
    private Object textureImage; 
    private BufferObject ssbo;
    private Material mat;
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

        Box box = new Box(1f, 1f, 1f);
        testGeom = new Geometry("ComputeBox", box);

        // ===============================================
        // 1. 创建 Storage Image
        // ===============================================
        computeTex = new Texture2D(512, 512, Image.Format.RGBA8);
        computeTex.getImage().setColorSpace(ColorSpace.Linear);
        computeTex.setMinFilter(com.jme3.texture.Texture.MinFilter.BilinearNoMipMaps);
        computeTex.setMagFilter(com.jme3.texture.Texture.MagFilter.Bilinear);

        try {
            Class<?> tiClass = Class.forName("com.jme3.texture.TextureImage");
            try {
                Object access = Class.forName("com.jme3.texture.TextureImage$Access").getEnumConstants()[2];
                textureImage = tiClass.getConstructor(Texture.class, int.class, int.class, access.getClass())
                        .newInstance(computeTex, 0, 0, access);
            } catch (Exception e) {
                textureImage = tiClass.getConstructor(Texture.class).newInstance(computeTex);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // ===============================================
        // 2. 创建 SSBO (现在只负责传递颜色，不传时间)
        // ===============================================
        ssbo = new BufferObject(); 
        ByteBuffer mySafeBuf = BufferUtils.createByteBuffer(4 * 4);
        mySafeBuf.asFloatBuffer().put(new float[]{1.0f, 1.0f, 1.0f, 1.0f});
        ssbo.setData(mySafeBuf);
        ssbo.setUpdateNeeded();

        // ===============================================
        // 3. 构建 Compute Shader
        // ===============================================
        computeShader = new Shader();
        Shader.ShaderType compType = Shader.ShaderType.Compute;

        String source = "#version 450\n"
                + "layout(local_size_x = 16, local_size_y = 16) in;\n"
                + "layout(set = 0, binding = 0, rgba8) uniform writeonly image2D uResultImage;\n"
                + "layout(set = 0, binding = 1, std430) buffer SSBO {\n"
                + "    vec4 params;\n"
                + "} ssbo;\n"
                // 【引擎原生态】：直接声明并使用引擎底层的全局时间变量 g_Time
                + "layout(set = 0, binding = 2) uniform PerDraw { float g_Time; };\n"
                + "void main() {\n"
                + "    ivec2 tc = ivec2(gl_GlobalInvocationID.xy);\n"
                + "    ivec2 size = imageSize(uResultImage);\n"
                + "    if(tc.x >= size.x || tc.y >= size.y) return;\n"
                + "    \n"
                + "    float r = float(tc.x) / size.x;\n"
                + "    float g = float(tc.y) / size.y;\n"
                + "    \n"
                + "    // 利用引擎底层的 g_Time 直接驱动水波纹动画\n"
                + "    float b = sin(g_Time * 2.0 + (r+g)*5.0) * 0.5 + 0.5;\n"
                + "    \n"
                + "    vec4 p = ssbo.params;\n"
                + "    if (length(p) < 0.01) p = vec4(1.0);\n"
                + "    \n"
                + "    imageStore(uResultImage, tc, vec4(r * p.r, g * p.g, b * p.b, 1.0));\n"
                + "}\n";

        computeShader.addSource(compType, "ComputeTest", source, "", "GLSL450");

        // ===============================================
        // 4. 材质挂载
        // ===============================================
        mat = new Material(assetManager, "VkCommon/MatDefs/Misc/VKUnshaded.j3md");
        mat.setTexture("ColorMap", computeTex);
        testGeom.setMaterial(mat);
        rootNode.attachChild(testGeom);
    }

    @Override
    public void update() {
        super.update();
        
        // 【优雅回归】：彻底告别 System.nanoTime()，使用引擎原生绝对时间
        float engineTime = timer.getTimeInSeconds();
        float tpf = timer.getTimePerFrame();

        // 预防 tpf 因为 Context 双重刷新 Bug 变为 0.0 的安全兜底
        float safeTpf = tpf <= 0.0001f ? 0.016f : tpf;

        // ===============================================
        // 每帧由 CPU 更新 SSBO 数据
        // ===============================================
        if (ssbo != null) {
            ByteBuffer buf = ssbo.getData();
            if (buf != null) {
                buf.clear();
                FloatBuffer fb = buf.asFloatBuffer();
                
                // 仅用引擎时间驱动 R 通道的呼吸效果，时间已经交由底层 UBO 处理了
                fb.put(0, (float) Math.abs(Math.sin(engineTime * 0.5f))); 
                fb.put(1, 1.0f); 
                fb.put(2, 1.0f); 
                fb.put(3, 1.0f); 
                
                ssbo.setUpdateNeeded();
            }
        }

        testGeom.rotate(0.5f * safeTpf, 0.8f * safeTpf, 0.2f * safeTpf);
        
        rootNode.updateLogicalState(safeTpf);
        rootNode.updateGeometricState();

        if (context != null && context.isRenderable()) {
            if (renderManager.getRenderer() instanceof VKRenderer) {
                VKRenderer vkRenderer = (VKRenderer) renderManager.getRenderer();

                if (ssbo != null) {
                    vkRenderer.updateShaderStorageBufferObjectData(ssbo);
                }

                if (computeShader != null && textureImage != null) {
                    vkRenderer.setShader(computeShader);
                    try {
                        java.lang.reflect.Method setTexMethod = null;
                        for(java.lang.reflect.Method m : vkRenderer.getClass().getMethods()){
                            if(m.getName().equals("setTextureImage") && m.getParameterCount() == 2) {
                                setTexMethod = m; break;
                            }
                        }
                        if (setTexMethod != null) setTexMethod.invoke(vkRenderer, 0, textureImage);
                    } catch (Exception e) {}
                    
                    vkRenderer.setShaderStorageBufferObject(1, ssbo);

                    try {
                        java.lang.reflect.Method dispatch = vkRenderer.getClass().getMethod("dispatchCompute", int.class, int.class, int.class);
                        dispatch.invoke(vkRenderer, 32, 32, 1);
                    } catch (Exception e) {}
                }
                vkRenderer.setShader(null);
            }
            mat.setTexture("ColorMap", computeTex);
            testGeom.setMaterial(mat);
            renderManager.render(safeTpf, true);

        }
    }

    public static void main(String[] args) {
        JmeSystem.setSystemDelegate(new VulkanSystemDelegate());

        AppSettings s = new AppSettings(true);
        s.setCustomRenderer(com.jme3.renderer.vulkan.context.LwjglVulkanContext.class);
        s.setWidth(1280);
        s.setHeight(720);
        s.setTitle("Vulkan Compute & SSBO Test - JME Native Time");

        VulkanComputeTest app = new VulkanComputeTest();
        app.setSettings(s);
        app.start();
    }
}