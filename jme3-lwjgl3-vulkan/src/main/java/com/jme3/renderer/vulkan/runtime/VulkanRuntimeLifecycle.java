package com.jme3.renderer.vulkan.runtime;

import com.jme3.renderer.vulkan.VkDebugFlags;
import com.jme3.renderer.vulkan.VulkanRuntime;
import com.jme3.renderer.vulkan.context.GlfwWindow;
import com.jme3.renderer.vulkan.context.VkContext;
import com.jme3.renderer.vulkan.frame.VulkanCommands;
import com.jme3.renderer.vulkan.frame.VulkanDescriptors;
import com.jme3.renderer.vulkan.frame.VulkanFrameDriver;
import com.jme3.renderer.vulkan.pipeline.PassKey;
import com.jme3.renderer.vulkan.resource.*;
import com.jme3.renderer.vulkan.shader.VulkanShaders;
import com.jme3.renderer.vulkan.swapchain.VulkanSwapchain;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_SAMPLE_COUNT_1_BIT;

public final class VulkanRuntimeLifecycle {

    private final VulkanRuntime owner;
    private final VulkanRuntimeState s;
    private final Logger log;
    private final boolean debug;

    public VulkanRuntimeLifecycle(VulkanRuntime owner, VulkanRuntimeState s, Logger log, boolean debug) {
        this.owner = owner;
        this.s = s;
        this.log = log;
        this.debug = s.settings.isGraphicsDebug(); 
    }

    public void init() throws IOException {
        initWindow();
        initContextAndPassKey();
        initResources();
        initDescriptorsAndMaterials();
        initPipelineAndCommands();
        initRenderTargetsAndFrameDriver();
    }

    public void recreateSwapchain() {
        if (s.renderTargetManager == null) {
            throw new IllegalStateException("renderTargetManager not initialized");
        }
        if (s.window == null) {
            return;
        }

        int fbW = s.window.fbWidth();
        int fbH = s.window.fbHeight();
        if (fbW <= 0 || fbH <= 0) {
            log.fine("[VulkanRuntime] recreateSwapchain skipped: framebuffer is zero-sized");
            s.reshapeRequested.set(true);
            return;
        }

        log.fine("[VulkanRuntime] recreateSwapchain begin: " + fbW + "x" + fbH);
        s.renderTargetManager.recreate(fbW, fbH);

        if (s.commands == null) {
            throw new IllegalStateException("commands not initialized");
        }

        VulkanSwapchain swapchain = s.renderTargetManager.getSwapchain();
        if (swapchain == null) {
            throw new IllegalStateException("swapchain not initialized after recreate");
        }

        s.commands.ensureForSwapchain(swapchain.getImageCount());
        log.fine("[VulkanRuntime] recreateSwapchain done, images=" + swapchain.getImageCount());
    }

    public void cleanup() {
        cleanupManagersAndCaches();
        cleanupFrameAndTargets();
        cleanupShadersDescriptorsAndFactory();
        cleanupContextAndWindow();
    }

    private void initWindow() {
        s.window = GlfwWindow.create(s.settings.getWidth(), s.settings.getHeight(), s.settings.getTitle(), (fbW, fbH) -> {
            if (fbW > 0 && fbH > 0) {
                s.reshapeRequested.set(true);
                if (log.isLoggable(Level.FINE)) {
                    log.fine("[VulkanRuntime] framebuffer resize callback: " + fbW + "x" + fbH);
                }
            } else {
                log.fine("[VulkanRuntime] framebuffer resize callback: zero-sized, defer recreate");
            }
        });
        log.info("[Vulkan] window framebuffer size = " + s.window.fbWidth() + " x " + s.window.fbHeight());
        log.info("[Vulkan] settings size (logical) = " + s.settings.getWidth() + " x " + s.settings.getHeight());
    }

    private void initContextAndPassKey() {
        s.vk = new VkContext(debug);
        s.vk.init(s.window);

        s.samplerManager = new VulkanSamplerManager(s.vk);
        s.defaultPassKey = new PassKey(1, s.vk.colorFormat(), s.vk.depthFormat(), VK_SAMPLE_COUNT_1_BIT);
    }

    private void initResources() {
        s.rf = new VkResourceFactory(s.vk);

        if (s.deferredReleaseQueue == null) {
            s.deferredReleaseQueue = new VulkanDeferredReleaseQueue();
        }

        s.meshManager = new VulkanMeshManager(
                s.rf,
                s.deferredReleaseQueue,
                owner::getCurrentFrameSlot
        );

        ByteBuffer white = memAlloc(4);
        try {
            white.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255).flip();
            // 【编译修复】：末尾传入 false，表示纯白占位贴图不需要做 ABGR 翻转
            s.whiteTex = s.rf.createTexture2DFromBuffer(white, 1, 1, VK_FORMAT_R8G8B8A8_UNORM, false);
        } finally {
            memFree(white);
        }

        s.textureManager = new VulkanTextureManager(
                s.rf,
                s.whiteTex,
                s.deferredReleaseQueue,
                owner::getCurrentFrameSlot
        );
    }

    private void initDescriptorsAndMaterials() {
        s.descriptors = new VulkanDescriptors(s.vk, s.rf, VulkanFrameDriver.MAX_FRAMES_IN_FLIGHT);
        s.descriptors.init(s.whiteTex);
        s.descriptors.setPerDrawAlignment(owner.getMinUniformBufferOffsetAlignment());

        s.materialDesc = new VulkanMaterialDescriptors(s.vk, 4096, s.stats);
        s.materialDesc.init();

        s.materialManager = new VulkanMaterialManager(
                s.materialDesc, s.stats,
                owner::getOrCreateSampler, owner::getOrCreateVkTexture, owner::getOffscreenJmeTex,
                () -> s.whiteTex, VkDebugFlags.DISABLE_MATERIAL_CACHE
        );

        int frames = VulkanFrameDriver.MAX_FRAMES_IN_FLIGHT;
        int maxSetsPerFrame = 16384;
        int maxSamplersPerFrame = maxSetsPerFrame * 8;

        s.frameDesc = new VulkanFrameDescriptors[frames];
        for (int i = 0; i < frames; i++) {
            s.frameDesc[i] = new VulkanFrameDescriptors(s.vk, maxSetsPerFrame, maxSamplersPerFrame);
            s.frameDesc[i].init();
        }
    }

    private void initPipelineAndCommands() throws IOException {
        s.shaders = new VulkanShaders(s.vk);

        s.pipelineManager = new VulkanPipelineManager(
                s.vk, s.shaders, s.defaultPassKey, s.stats
        );
        s.pipelineManager.init();

        s.commands = new VulkanCommands(s.vk);
        s.commands.init();
    }

    private void initRenderTargetsAndFrameDriver() {
        s.renderTargetManager = new VulkanRenderTargetManager(s.vk, s.rf, s.settings.isVSync());
        s.renderTargetManager.init(s.window.fbWidth(), s.window.fbHeight());

        s.commands.ensureForSwapchain(s.renderTargetManager.getSwapchain().getImageCount());

        s.frameDriver = new VulkanFrameDriver(s.vk);
        s.frameDriver.init();

        s.window.show();
        s.lastTimeNs = System.nanoTime();
    }

    private void cleanupManagersAndCaches() {
        if (s.textureManager != null) {
            s.textureManager.destroyCachedTextures();
            s.textureManager = null;
        }
        if (s.materialManager != null) {
            s.materialManager.destroy();
            s.materialManager = null;
        }
        if (s.materialDesc != null) {
            s.materialDesc.destroy();
            s.materialDesc = null;
        }
        if (s.pipelineManager != null) {
            s.pipelineManager.destroy();
            s.pipelineManager = null;
        }
        if (s.meshManager != null) {
            s.meshManager.destroyAll();
            s.meshManager = null;
        }
        if (s.frameDesc != null) {
            for (VulkanFrameDescriptors fd : s.frameDesc) {
                if (fd != null) {
                    fd.destroy();
                }
            }
            s.frameDesc = null;
        }
        if (s.deferredReleaseQueue != null) {
            s.deferredReleaseQueue.flushAll();
            s.deferredReleaseQueue = null;
        }
    }

    private void cleanupFrameAndTargets() {
        if (s.frameDriver != null) {
            s.frameDriver.destroy();
            s.frameDriver = null;
        }
        if (s.commands != null) {
            s.commands.destroy();
            s.commands = null;
        }
        if (s.renderTargetManager != null) {
            s.renderTargetManager.cleanup();
            s.renderTargetManager = null;
        }
    }

    private void cleanupShadersDescriptorsAndFactory() {
        if (s.shaders != null) {
            s.shaders.destroy();
            s.shaders = null;
        }
        if (s.descriptors != null) {
            s.descriptors.destroy();
            s.descriptors = null;
        }
        if (s.rf != null && s.whiteTex != null) {
            s.rf.destroyTexture(s.whiteTex);
            s.whiteTex = null;
        }
        if (s.rf != null) {
            s.rf.destroy();
            s.rf = null;
        }
        if (s.samplerManager != null) {
            s.samplerManager.destroy();
            s.samplerManager = null;
        }
    }

    private void cleanupContextAndWindow() {
        if (s.vk != null) {
            s.vk.destroy();
            s.vk = null;
        }
        if (s.window != null) {
            s.window.destroy();
            s.window = null;
        }
    }

    private VkContext vk() {
        return s.vk;
    }
}