package com.jme3.renderer.vulkan.swapchain;

import com.jme3.renderer.vulkan.resource.VkDepthResources;
import com.jme3.renderer.vulkan.resource.VkResourceFactory;
import com.jme3.renderer.vulkan.context.VkContext;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.KHRSurface.VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

public final class VulkanSwapchain {

    private final VkContext vk;
    private final VkResourceFactory rf;
    private final boolean vsync; // 新增 vsync 状态

    private long swapchain;
    private long[] images;
    private long[] imageViews;

    private VkDepthResources depth;

    private int width;
    private int height;
    private int[] imageLayouts; 

    private int depthLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    public int getDepthLayout() {
        return depthLayout;
    }

    public void setDepthLayout(int layout) {
        this.depthLayout = layout;
    }

    public int getImageLayout(int i) {
        return imageLayouts != null ? imageLayouts[i] : VK_IMAGE_LAYOUT_UNDEFINED;
    }

    public void setImageLayout(int i, int layout) {
        if (imageLayouts != null) {
            imageLayouts[i] = layout;
        }
    }

    // 修改构造函数，增加 vsync
    public VulkanSwapchain(VkContext vk, VkResourceFactory rf, boolean vsync) {
        this.vk = vk;
        this.rf = rf;
        this.vsync = vsync;
    }

    public long getSwapchain() {
        return swapchain;
    }

    public int getImageCount() {
        return images != null ? images.length : 0;
    }

    public long getImage(int i) {
        return images[i];
    }

    public long getImageView(int i) {
        return imageViews[i];
    }

    public long getDepthView() {
        return depth != null ? depth.view : 0L;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public void recreate(int w, int h) {
        this.width = w;
        this.height = h;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 根据 vsync 状态选择 Present Mode
            int presentMode = vsync ? VK_PRESENT_MODE_FIFO_KHR : VK_PRESENT_MODE_IMMEDIATE_KHR;

            VkSwapchainCreateInfoKHR scCI = VkSwapchainCreateInfoKHR.calloc(stack)
                    .sType$Default()
                    .surface(vk.surface())
                    .minImageCount(2) 
                    .imageFormat(vk.colorFormat())
                    .imageColorSpace(vk.colorSpace())
                    .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
                    .imageArrayLayers(1)
                    .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .preTransform(VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
                    .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                    .presentMode(presentMode); // 应用 Present Mode

            scCI.imageExtent().set(w, h);

            LongBuffer pSc = stack.mallocLong(1);
            int err = vkCreateSwapchainKHR(vk.device(), scCI, null, pSc);
            if (err != VK_SUCCESS) {
                throw new RuntimeException("vkCreateSwapchainKHR failed: " + err);
            }
            swapchain = pSc.get(0);

            IntBuffer pCount = stack.ints(0);
            vkGetSwapchainImagesKHR(vk.device(), swapchain, pCount, null);

            int count = pCount.get(0);
            LongBuffer pImgs = stack.mallocLong(count);
            vkGetSwapchainImagesKHR(vk.device(), swapchain, pCount, pImgs);

            images = new long[count];
            imageViews = new long[count];

            imageLayouts = new int[count];
            for (int i = 0; i < count; i++) {
                imageLayouts[i] = VK_IMAGE_LAYOUT_UNDEFINED;
            }

            for (int i = 0; i < count; i++) {
                long img = pImgs.get(i);
                images[i] = img;
                imageViews[i] = rf.createImageView(img, vk.colorFormat(), VK_IMAGE_ASPECT_COLOR_BIT);
            }

            depth = rf.createDepth(w, h, vk.depthFormat());
        }
        depthLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    }

    public void cleanup() {
        if (imageViews != null) {
            for (long v : imageViews) {
                if (v != 0L) {
                    vkDestroyImageView(vk.device(), v, null);
                }
            }
            imageViews = null;
        }

        if (depth != null) {
            rf.destroyDepth(depth);
            depth = null;
        }

        if (swapchain != 0L) {
            vkDestroySwapchainKHR(vk.device(), swapchain, null);
            swapchain = 0L;
        }

        images = null;
        imageLayouts = null;
        depthLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    }

    public long getDepthImage() {
        return depth != null ? depth.image : 0L;
    }
}