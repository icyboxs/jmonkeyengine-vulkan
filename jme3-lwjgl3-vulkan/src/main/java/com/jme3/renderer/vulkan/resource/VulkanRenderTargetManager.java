package com.jme3.renderer.vulkan.resource;

import com.jme3.renderer.vulkan.context.VkContext;
import com.jme3.renderer.vulkan.swapchain.VulkanSwapchain;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;

public final class VulkanRenderTargetManager {

    private final VkContext vk;
    private final VkResourceFactory rf;
    private final boolean vsync; // 新增 vsync 字段

    private VulkanSwapchain swapchain;

    private VkOffscreenTarget offscreen;
    private Texture2D offscreenJmeTex;

    // 构造函数传入 vsync
    public VulkanRenderTargetManager(VkContext vk, VkResourceFactory rf, boolean vsync) {
        if (vk == null) {
            throw new IllegalArgumentException("vk is null");
        }
        if (rf == null) {
            throw new IllegalArgumentException("rf is null");
        }
        this.vk = vk;
        this.rf = rf;
        this.vsync = vsync;
    }

// init 和 recreate 需要传递 srgb
    public void init(int width, int height, boolean srgb) {
        if (swapchain != null) {
            return;
        }
        swapchain = new VulkanSwapchain(vk, rf, vsync);
        swapchain.recreate(width, height, srgb);
        recreateOffscreen(width, height, srgb);
    }

    public void recreate(int width, int height, boolean srgb) {
        if (swapchain == null) {
            init(width, height, srgb);
            return;
        }
        vk.waitIdle();
        swapchain.cleanup();
        swapchain.recreate(width, height, srgb);
        recreateOffscreen(width, height, srgb);
    }

    public void cleanup() {
        destroyOffscreen();
        offscreenJmeTex = null;

        if (swapchain != null) {
            swapchain.cleanup();
            swapchain = null;
        }
    }

    public VulkanSwapchain getSwapchain() {
        return swapchain;
    }

    public VkOffscreenTarget getOffscreen() {
        return offscreen;
    }

    public Texture2D getOffscreenJmeTex() {
        return offscreenJmeTex;
    }

    public boolean isOffscreenCarrier(Texture tex) {
        return tex != null && tex == offscreenJmeTex;
    }

    public VkTexture getOffscreenColorOrWhite(VkTexture whiteTex) {
        if (offscreen == null || offscreen.color == null) {
            return whiteTex;
        }
        return offscreen.color;
    }

    private void recreateOffscreen(int w, int h, boolean srgb) {
        destroyOffscreen();

        offscreen = new VkOffscreenTarget();
        offscreen.width = w;
        offscreen.height = h;

        offscreen.color = rf.createColorAttachmentTexture(w, h, vk.getColorFormat(srgb));
        offscreen.depth = rf.createDepth(w, h, vk.depthFormat());

        offscreen.colorLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        offscreen.depthLayout = VK_IMAGE_LAYOUT_UNDEFINED;

        if (offscreenJmeTex == null) {
            offscreenJmeTex = new Texture2D();
            offscreenJmeTex.setWrap(Texture.WrapMode.EdgeClamp);
            offscreenJmeTex.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
            offscreenJmeTex.setMagFilter(Texture.MagFilter.Bilinear);
            offscreenJmeTex.setAnisotropicFilter(0);
        }
    }

    private void destroyOffscreen() {
        if (offscreen == null) {
            return;
        }

        if (offscreen.color != null) {
            rf.destroyTexture(offscreen.color);
            offscreen.color = null;
        }
        if (offscreen.depth != null) {
            rf.destroyDepth(offscreen.depth);
            offscreen.depth = null;
        }

        offscreen = null;
    }
}
