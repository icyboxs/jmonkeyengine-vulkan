package com.jme3.renderer.vulkan.runtime;

import com.jme3.renderer.vulkan.context.GlfwWindow;
import com.jme3.renderer.vulkan.context.VkContext;
import com.jme3.renderer.vulkan.frame.VulkanCommands;
import com.jme3.renderer.vulkan.frame.VulkanDescriptors;
import com.jme3.renderer.vulkan.frame.VulkanFrameDriver;
import com.jme3.renderer.vulkan.pipeline.PassKey;
import com.jme3.renderer.vulkan.resource.VkResourceFactory;
import com.jme3.renderer.vulkan.resource.VkTexture;
import com.jme3.renderer.vulkan.resource.VulkanBufferObjectManager;
import com.jme3.renderer.vulkan.resource.VulkanDeferredReleaseQueue;
import com.jme3.renderer.vulkan.resource.VulkanFrameBufferManager;
import com.jme3.renderer.vulkan.resource.VulkanFrameDescriptors;
import com.jme3.renderer.vulkan.resource.VulkanMaterialDescriptors;
import com.jme3.renderer.vulkan.resource.VulkanMaterialManager;
import com.jme3.renderer.vulkan.resource.VulkanMeshManager;
import com.jme3.renderer.vulkan.resource.VulkanPipelineManager;
import com.jme3.renderer.vulkan.resource.VulkanRenderTargetManager;
import com.jme3.renderer.vulkan.resource.VulkanSamplerManager;
import com.jme3.renderer.vulkan.resource.VulkanTextureManager;
import com.jme3.renderer.vulkan.shader.VulkanShaders;
import com.jme3.system.AppSettings;

import java.util.concurrent.atomic.AtomicBoolean;

public final class VulkanRuntimeState {

    public final AppSettings settings;
    public final AtomicBoolean reshapeRequested = new AtomicBoolean(false);

    public volatile boolean forceFixedUboLayout = false;

    public GlfwWindow window;
    public VkContext vk;
    public VkResourceFactory rf;

    public VulkanDescriptors descriptors;
    public VulkanShaders shaders;
    public VulkanPipelineManager pipelineManager;
    public VulkanRenderTargetManager renderTargetManager;
    public VulkanCommands commands;
    public VulkanFrameDriver frameDriver;

    public float angle = 0f;
    public long lastTimeNs = 0L;

    public VulkanMeshManager meshManager;
    public VkTexture whiteTex;
    public VulkanTextureManager textureManager;
    public VulkanFrameBufferManager frameBufferManager;
    public VulkanFrameDescriptors[] frameDesc;
    public VulkanMaterialDescriptors materialDesc;
    public VulkanMaterialManager materialManager;
    public PassKey defaultPassKey;
    public VulkanSamplerManager samplerManager;

    public final VulkanRuntimeStats stats = new VulkanRuntimeStats();

    public VulkanDeferredReleaseQueue deferredReleaseQueue;
    public volatile int currentFrameSlot = 0;

    public boolean vsync = true; // 默认 true

    // --- sRGB 线性工作流控制开关 ---
    public volatile boolean mainFbSrgb = false;
    public volatile boolean linearizeSrgbImages = false;

    public volatile int defaultAnisotropicFilter = 1;
    
    public VulkanBufferObjectManager bufferObjectManager;
    public VulkanRuntimeState(AppSettings settings) {
        this.settings = settings;
        this.vsync = settings.isVSync();

        // 初始化时可同步 jME3 的初始 Gamma 设置
        this.mainFbSrgb = settings.isGammaCorrection();
        this.linearizeSrgbImages = settings.isGammaCorrection();
    }

}
