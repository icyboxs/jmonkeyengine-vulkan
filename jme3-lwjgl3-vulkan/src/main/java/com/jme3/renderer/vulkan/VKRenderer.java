package com.jme3.renderer.vulkan;

import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.renderer.*;
import com.jme3.renderer.vulkan.binding.impl.DefaultDescriptorSetBinder;
import com.jme3.renderer.vulkan.cmd.DrawCmd;
import com.jme3.renderer.vulkan.cmd.DrawCmdBuilder;
import com.jme3.renderer.vulkan.cmd.RendererStateSnapshot;
import com.jme3.renderer.vulkan.frame.DefaultFrameRecorder;
import com.jme3.renderer.vulkan.frame.DrawExecutor;
import com.jme3.renderer.vulkan.frame.VulkanFrameInfo;
import com.jme3.renderer.vulkan.queue.DrawQueue;
import com.jme3.renderer.vulkan.state.FrontendStateTracker;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;

import com.jme3.shader.Shader;
import com.jme3.shader.bufferobject.BufferObject;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.TextureImage;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author icyboxs
 */
public final class VKRenderer implements Renderer, VkCommandRecorder {

    private static final Logger LOGGER = Logger.getLogger(VKRenderer.class.getName());

    private final VulkanRuntime runtime;

    private final Statistics stats = new Statistics();
    private final EnumSet<Caps> caps = EnumSet.noneOf(Caps.class);
    private final EnumMap<Limits, Integer> limits = new EnumMap<>(Limits.class);

    private boolean initialized = false;

    // --- 子系统 ---
    private final FrontendStateTracker stateTracker = new FrontendStateTracker();
    private final DrawQueue drawQueue = new DrawQueue();

    private DrawCmdBuilder drawCmdBuilder;
    private DrawExecutor drawExecutor;
    private DefaultFrameRecorder frameRecorder;

    public VKRenderer(VulkanRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public void initialize() {
        if (initialized) {
            return;
        }

        runtime.ensureInitialized();

        drawCmdBuilder = new DrawCmdBuilder(runtime);

        DefaultDescriptorSetBinder binder = new DefaultDescriptorSetBinder(
                runtime,
                new com.jme3.renderer.vulkan.binding.plan.FrequencyLayerRegistry(),
                new com.jme3.renderer.vulkan.binding.cache.FrameSetCache(),
                new com.jme3.renderer.vulkan.binding.provider.ObjectHighBindingProvider(runtime)
        );

        drawExecutor = new DrawExecutor(binder);
        frameRecorder = new DefaultFrameRecorder(stateTracker, drawQueue, drawExecutor);

        initCapsAndLimits();
        initialized = true;

        if (LOGGER.isLoggable(Level.INFO)) {
            LOGGER.info("LwjglVKRenderer initialized. Caps=" + caps + ", limits=" + limits);
        }
    }

    private void initCapsAndLimits() {
        caps.clear();
        limits.clear();
        safeAddCap(Caps.GLSL310);
        safeAddCap(Caps.GLSL330);
        safeAddCap(Caps.GLSL400);
        safeAddCap(Caps.GLSL410);
        safeAddCap(Caps.GLSL420);
        safeAddCap(Caps.GLSL430);
        safeAddCap(Caps.GLSL440);
        safeAddCap(Caps.GLSL450);
        safeAddCap("VertexBufferArray");
        safeAddCap("FrameBuffer");
        safeAddCap("FrameBufferMRT");
        safeAddCap("TextureNonPowerOfTwo");
        safeAddCap("TextureCompressionS3TC");
        safeAddCap("TextureAnisotropicFilter");
        safeAddCap("DepthTexture");
        safeAddCap("PackedDepthStencilBuffer");
        safeAddCap("SeparateShaderObjects");

        safePutLimit("TextureImageUnits", 16);
        safePutLimit("VertexTextureUnits", 16);
        safePutLimit("FragmentTextureUnits", 16);
        safePutLimit("CombinedTextureUnits", 16);
        safePutLimit("MaxTextureSize", 16384);
        safePutLimit("MaxCubeMapSize", 16384);
        safePutLimit("MaxVertexUniformVectors", 4096);
        safePutLimit("MaxFragmentUniformVectors", 4096);
        safePutLimit("MaxVertexAttribs", 16);
        safePutLimit("MaxSamples", 4);
        safeAddCap(Caps.Srgb);
    }

    private void safeAddCap(Caps c) {
        if (c != null) {
            caps.add(c);
        }
    }

    private void safeAddCap(String capName) {
        try {
            caps.add(Caps.valueOf(capName));
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void safePutLimit(String limitName, int value) {
        try {
            limits.put(Limits.valueOf(limitName), value);
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override
    public EnumSet<Caps> getCaps() {
        return caps;
    }

    @Override
    public EnumMap<Limits, Integer> getLimits() {
        return limits;
    }

    @Override
    public Statistics getStatistics() {
        return stats;
    }

    @Override
    public void renderMesh(Mesh mesh, int lod, int count, VertexBuffer[] instanceData) {
        if (drawQueue.isFull() || mesh == null || drawCmdBuilder == null) {
            return;
        }

        // ========================================================
        // [核心修复]：检查并同步 jME3 的动态 Mesh 变更
        // BitmapText、粒子等会频繁修改 NIO Buffer 并标记 isUpdateNeeded
        // ========================================================
        for (VertexBuffer vb : mesh.getBufferList().getArray()) {
            if (vb != null && vb.isUpdateNeeded()) {
                // 1. 通知后端作废该 VBO 对应的 GPU 缓存
                updateBufferData(vb);
                // 2. 必须清除标记！否则每帧都会重复重建引发严重卡顿
                vb.clearUpdateNeeded();
            }
        }

        RendererStateSnapshot snap = stateTracker.createSnapshot();
        DrawCmd dc = drawCmdBuilder.build(mesh, lod, count, instanceData, snap);

        if (dc != null) {
            drawQueue.enqueue(dc);
        }
    }

    @Override
    public void recordFrame(VkCommandBuffer cmd, int swapchainIndex, int frameIndex, VulkanFrameInfo frame) {
        if (frameRecorder != null) {
            frameRecorder.recordFrame(cmd, swapchainIndex, frameIndex, frame);
        }
    }

    @Override
    public void modifyTexture(Texture tex, Image pixels, int x, int y) {
        if (tex == null) {
            return;
        }
        if (pixels != null) {
            tex.setImage(pixels);
        }
        try {
            runtime.invalidateVkTexture(tex);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void deleteImage(Image image) {
        if (image == null) {
            return;
        }
        try {
            runtime.invalidateVkTextureByImage(image);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void updateBufferData(VertexBuffer vb) {
        if (vb == null) {
            return;
        }
        try {
            runtime.invalidateMeshGpuByVertexBuffer(vb);
        } catch (Throwable ignored) {
            runtime.invalidateAllMeshGpu();
        }
    }

    @Override
    public void deleteBuffer(VertexBuffer vb) {
        if (vb == null) {
            return;
        }
        try {
            runtime.invalidateMeshGpuByVertexBuffer(vb);
        } catch (Throwable ignored) {
            runtime.invalidateAllMeshGpu();
        }
    }

    @Override
    public void cleanup() {
        stateTracker.reset();
        drawQueue.clear();

        if (drawExecutor != null) {
            drawExecutor.cleanup();
        }

        drawCmdBuilder = null;
        drawExecutor = null;
        frameRecorder = null;
        initialized = false;
    }

    public void discardPendingDraws() {
        drawQueue.clear();
    }

    // --- State Tracker 代理委派 ---
    @Override
    public void clearBuffers(boolean color, boolean depth, boolean stencil) {
        stateTracker.setClearBuffers(color, depth, stencil);
    }

    @Override
    public void setBackgroundColor(ColorRGBA color) {
        stateTracker.setBackgroundColor(color);
    }

    @Override
    public void applyRenderState(RenderState state) {
        stateTracker.applyRenderState(state);
    }

    @Override
    public void setViewPort(int x, int y, int width, int height) {
        stateTracker.setViewPort(x, y, width, height);
    }

    @Override
    public void setClipRect(int x, int y, int width, int height) {
        stateTracker.setClipRect(x, y, width, height);
    }

    @Override
    public void clearClipRect() {
        stateTracker.clearClipRect();
    }

    @Override
    public void setShader(Shader shader) {
        stateTracker.setShader(shader);
    }

    @Override
    public void setFrameBuffer(FrameBuffer fb) {
        stateTracker.setFrameBuffer(fb);
    }

    @Override
    public FrameBuffer getCurrentFrameBuffer() {
        return stateTracker.getCurrentFb();
    }

    @Override
    public void setTexture(int unit, Texture tex) throws TextureUnitException {
        stateTracker.setTexture(unit, tex);
    }

    // --- 空实现或未支持的方法 ---
    @Override
    @Deprecated
    public void invalidateState() {
        //用于清空 OpenGL 隐藏的驱动状态缓存。您的 DrawCmdBuilder 每次 Draw 都是全新构建的 RendererStateSnapshot 快照，天然免疫状态残留问题。
    }

    @Override
    public void setDepthRange(float start, float end) {
        stateTracker.setDepthRange(start, end);
    }

    @Override
    @Deprecated
    public void postFrame() {
        //以前用来挂载 glfwSwapBuffers 的地方。现在呈现逻辑已被您的 VulkanFrameDriver.renderOneFrame() 完美封装（包含 Semaphore 和 Fence 轮转）
    }

    @Override
    public void deleteShader(Shader shader) {
        if (shader == null) {
            return;
        }

        // 1. 防御性编程：如果前端刚好正在绑定这个即将被销毁的 Shader，强制将其解绑
        // 杜绝悬空指针导致后续构建 DrawCmd 时引发崩溃
        if (stateTracker.getCurrentShader() == shader) {
            stateTracker.setShader(null);
        }

        // 2. 从命令构建器的前端缓存中主动移除对该 Shader 对象的引用
        if (drawCmdBuilder != null) {
            drawCmdBuilder.deleteShader(shader);
        }

        // 3. 标记 jME3 NativeObject 已被回收，从 NativeObjectManager 的追踪队列中安全移除
        shader.resetObject();

        // 注：
        // 在这套 Vulkan 架构中，底层的 VkShaderModule 和 VulkanPipeline 是完全
        // 基于 GLSL 源码的 Hash 值在 VulkanShaders 和 VulkanPipelineManager 中全局去重缓存的。
        // 我们不在此处销毁底层的 Vulkan 资源，而是交由 VulkanRuntime 在 cleanup 时统一销毁。
        // 这样既能最大化复用，又能避免运行时出现 "销毁又重复编译" 导致的严重掉帧卡顿。
    }

    @Override
    public void deleteShaderSource(Shader.ShaderSource source) {
        if (source == null) {
            return;
        }

        // 单纯告知 jME3 引擎层该对象已被释放
        // Vulkan 后端不单独管理零碎的 ShaderSource 句柄（不像 OpenGL 的 glCreateShader）。
        // 我们是将完整的 Shader 提取组装后统一编译为 SPIR-V 并由 VulkanShaders 缓存的。
        source.resetObject();
    }

    @Override
    public void copyFrameBuffer(FrameBuffer src, FrameBuffer dst, boolean copyDepth) {
        copyFrameBuffer(src, dst, true, copyDepth);
    }

    @Override
    public void copyFrameBuffer(FrameBuffer src, FrameBuffer dst, boolean copyColor, boolean copyDepth) {
        if (!copyColor && !copyDepth) {
            return;
        }

        // 生成命令并排入队列，等待 RecordFrame 阶段在 RenderPass 之外统一执行
        com.jme3.renderer.vulkan.cmd.CopyCmd cmd = com.jme3.renderer.vulkan.cmd.CopyCmd.acquire();
        cmd.src = src;
        cmd.dst = dst;
        cmd.copyColor = copyColor;
        cmd.copyDepth = copyDepth;
        drawQueue.enqueueCopy(cmd);
    }

    @Override
    @Deprecated
    public void setMainFrameBufferOverride(FrameBuffer fb) {
    }

    @Override
    public void readFrameBuffer(FrameBuffer fb, ByteBuffer byteBuf) {
        // 如果 JME3 没有要求特定格式，就默认按最标准的 RGBA8 返回
        readFrameBufferWithFormat(fb, byteBuf, Image.Format.RGBA8);
    }

    @Override
    public void readFrameBufferWithFormat(FrameBuffer fb, ByteBuffer byteBuf, Image.Format format) {
        if (byteBuf == null || format == null) {
            return;
        }
        try {
            runtime.readFrameBuffer(fb, byteBuf, format);
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Failed to read FrameBuffer pixels", t);
        }
    }

    @Override
    public void deleteFrameBuffer(FrameBuffer fb) {
        if (fb == null) {
            return;
        }

        // 如果前端刚好正在绑定这块即将被销毁的 FrameBuffer，强制将其解绑。
        // 这是为了防止出现悬空指针（Dangling Pointer）导致后续 Draw 渲染时找不到显存而发生崩溃
        if (stateTracker.getCurrentFb() == fb) {
            stateTracker.setFrameBuffer(null);
        }

        try {
            // 委托给 Runtime 进行底层显存的安全销毁，通过延迟队列销毁机制防止 Vulkan 崩溃
            runtime.deleteFrameBuffer(fb);
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Failed to delete FrameBuffer", t);
        }
    }

    @Override
    @Deprecated
    public void popDebugGroup() {
        //Vulkan 原生支持 vkCmdBeginDebugUtilsLabelEXT，但性价比极低。现代 Vulkan 开发严重依赖 RenderDoc / Nsight 等外部工具，引擎层的标签打点可以省略。保持为空
    }

    @Override
    @Deprecated
    public void pushDebugGroup(String name) {
        //Vulkan 原生支持 vkCmdBeginDebugUtilsLabelEXT，但性价比极低。现代 Vulkan 开发严重依赖 RenderDoc / Nsight 等外部工具，引擎层的标签打点可以省略。保持为空
    }

    @Override
    @Deprecated
    public void resetGLObjects() {
        //用于处理 OpenGL Context 丢失。您的 Vulkan 架构中，设备丢失恢复完全由 VulkanFrameDriver 触发 Swapchain Recreate，且 VMA 统一管理内存，不需要这种全局的旧式 ID 重置。
    }

    @Override
    public void setDefaultAnisotropicFilter(int level) {
        runtime.setDefaultAnisotropicFilter(level);
    }

    @Override
    public int getDefaultAnisotropicFilter() {
        return runtime.getDefaultAnisotropicFilter();
    }

    @Override
    public void setAlphaToCoverage(boolean value) {
        stateTracker.setAlphaToCoverage(value);
    }

    @Override
    public boolean getAlphaToCoverage() {
        return stateTracker.getAlphaToCoverage();
    }

    @Override
    public void setMainFrameBufferSrgb(boolean srgb) {
        runtime.setMainFrameBufferSrgb(srgb);
    }

    @Override
    public boolean isMainFrameBufferSrgb() {
        return runtime.isMainFrameBufferSrgb();
    }

    @Override
    public void setLinearizeSrgbImages(boolean linearize) {
        runtime.setLinearizeSrgbImages(linearize);
    }

    @Override
    public boolean isLinearizeSrgbImages() {
        return runtime.isLinearizeSrgbImages();
    }

    @Override
    @Deprecated
    public int[] generateProfilingTasks(int numTasks) {
        //这是 JME3 引擎自带的 GPU 时间线分析器。在 Vulkan 中实现起来需要使用 VkQueryPool 写 Timestamp，还要处理乱序队列同步，性价比极低。现代开发应直接使用 RenderDoc 或 Nsight 等外部工具。保持返回 0 / false / new int[0] 即可。
        return new int[0];
    }

    @Override
    @Deprecated
    public void startProfiling(int taskId) {
        //这是 JME3 引擎自带的 GPU 时间线分析器。在 Vulkan 中实现起来需要使用 VkQueryPool 写 Timestamp，还要处理乱序队列同步，性价比极低。现代开发应直接使用 RenderDoc 或 Nsight 等外部工具。保持返回 0 / false / new int[0] 即可。
    }

    @Override
    @Deprecated
    public void stopProfiling() {
        //这是 JME3 引擎自带的 GPU 时间线分析器。在 Vulkan 中实现起来需要使用 VkQueryPool 写 Timestamp，还要处理乱序队列同步，性价比极低。现代开发应直接使用 RenderDoc 或 Nsight 等外部工具。保持返回 0 / false / new int[0] 即可。
    }

    @Override
    @Deprecated
    public long getProfilingTime(int taskId) {
        //这是 JME3 引擎自带的 GPU 时间线分析器。在 Vulkan 中实现起来需要使用 VkQueryPool 写 Timestamp，还要处理乱序队列同步，性价比极低。现代开发应直接使用 RenderDoc 或 Nsight 等外部工具。保持返回 0 / false / new int[0] 即可。
        return 0;
    }

    @Override
    @Deprecated
    public boolean isTaskResultAvailable(int taskId) {
        //内置 GPU 耗时查询。在 Vulkan 中通过 VkQueryPool 写 Timestamp 并在乱序队列中同步非常复杂且开销大。这部分功能完全交给 RenderDoc 等外部抓帧工具即可。返回 new int[0]、false 或 0。
        return false;
    }

    @Override
    public float getMaxLineWidth() {
        //现代 Vulkan 推荐线宽永远固定为 1.0f。大于 1 的线宽需要显卡支持 wideLines 特性（Mac 和移动端大多不支持）。直接保持 return 1.0f;
        return 1.0f;
    }

    @Override
    public void setTextureImage(int unit, TextureImage tex) throws TextureUnitException {
        stateTracker.setTextureImage(unit, tex);
    }

    @Override
    public void updateShaderStorageBufferObjectData(com.jme3.shader.bufferobject.BufferObject bo) {
        if (bo != null) {
            runtime.updateBufferObjectData(bo);
        }
    }

    @Override
    public void setShaderStorageBufferObject(int bindingPoint, com.jme3.shader.bufferobject.BufferObject bufferObject) {
        stateTracker.setShaderStorageBufferObject(bindingPoint, bufferObject);
    }

    @Override
    @Deprecated
    public void updateUniformBufferObjectData(com.jme3.shader.bufferobject.BufferObject bo) {
    }

    @Override
    @Deprecated
    public void setUniformBufferObject(int bindingPoint, com.jme3.shader.bufferobject.BufferObject bufferObject) {
    }

    @Override
    public void deleteBuffer(BufferObject bo) {
        if (bo != null) {
            runtime.deleteBufferObject(bo);
        }
    }
    
    public void dispatchCompute(int numGroupsX, int numGroupsY, int numGroupsZ) {
        if (drawQueue.isFull() || drawCmdBuilder == null) {
            return;
        }
        com.jme3.renderer.vulkan.cmd.RendererStateSnapshot snap = stateTracker.createSnapshot();
        com.jme3.renderer.vulkan.cmd.ComputeCmd cmd = drawCmdBuilder.buildCompute(numGroupsX, numGroupsY, numGroupsZ, snap);
        if (cmd != null) {
            drawQueue.enqueueCompute(cmd);
        }
    }
}
