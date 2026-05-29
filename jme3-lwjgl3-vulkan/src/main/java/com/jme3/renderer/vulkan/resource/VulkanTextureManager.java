package com.jme3.renderer.vulkan.resource;

import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import static org.lwjgl.vulkan.VK10.*;

public final class VulkanTextureManager {

    private final VulkanDeferredReleaseQueue deferredReleaseQueue;
    private final IntSupplier frameIndexSupplier;
    private final BooleanSupplier linearizeSrgbSupplier;
    private final VkResourceFactory rf;
    private final Map<Texture, VkTexture> textureCache = new WeakHashMap<>();
    private VkTexture whiteTex;

    private ByteBuffer convertBuffer;

    public VulkanTextureManager(VkResourceFactory rf, VkTexture whiteTex,
            VulkanDeferredReleaseQueue deferredReleaseQueue,
            IntSupplier frameIndexSupplier,
            BooleanSupplier linearizeSrgbSupplier) {
        if (rf == null) {
            throw new IllegalArgumentException("rf is null");
        }
        this.rf = rf;
        this.whiteTex = whiteTex;
        this.deferredReleaseQueue = deferredReleaseQueue;
        this.frameIndexSupplier = frameIndexSupplier;
        this.linearizeSrgbSupplier = linearizeSrgbSupplier;
    }

    public void setWhiteTex(VkTexture whiteTex) {
        this.whiteTex = whiteTex;
    }

    public VkTexture getOrCreate(Texture tex) {
        if (tex == null) {
            return whiteTex;
        }

        VkTexture cached = textureCache.get(tex);
        if (cached != null) {
            return cached;
        }

        if (tex.getType() != Texture.Type.TwoDimensional) {
            throw new UnsupportedOperationException("Only Texture2D supported, got: " + tex.getType());
        }

        Image img = tex.getImage();
        if (img == null) {
            return whiteTex;
        }

        int w = img.getWidth();
        int h = img.getHeight();
        if (w <= 0 || h <= 0) {
            return whiteTex;
        }

        ByteBuffer data = img.getData(0);

        boolean isSrgb = linearizeSrgbSupplier.getAsBoolean()
                && img.getColorSpace() == com.jme3.texture.image.ColorSpace.sRGB;

        // ========================================================
        // 【核心封印解除】：彻底移除对 isStorage 和 TextureImage 的严格校验！
        // 只要前端需要一张没数据的贴图，我们都在 GPU 端直接开辟一块支持 STORAGE_BIT 的空白显存，
        // 让 Compute Shader 可以尽情挥洒！
        // ========================================================
        if (data == null) {
            int defaultFormat = isSrgb ? org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SRGB : org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
            Image.Format fmt = img.getFormat();
            if (fmt == Image.Format.Luminance8) {
                defaultFormat = org.lwjgl.vulkan.VK10.VK_FORMAT_R8_UNORM;
            } else if (fmt == Image.Format.RGBA16F) {
                defaultFormat = org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
            } else if (fmt == Image.Format.RGBA32F) {
                defaultFormat = org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT;
            } else if (fmt == Image.Format.Luminance16F) {
                defaultFormat = org.lwjgl.vulkan.VK10.VK_FORMAT_R16_SFLOAT;
            } else if (fmt == Image.Format.Luminance32F) {
                defaultFormat = org.lwjgl.vulkan.VK10.VK_FORMAT_R32_SFLOAT;
            }

            VkTexture vkTex = rf.createEmptyTexture2D(w, h, defaultFormat);
            textureCache.put(tex, vkTex);
            return vkTex;
        }

        int vkFormat;
        ByteBuffer pixels;
        Image.Format fmt = img.getFormat();
        switch (fmt) {
            case RGBA8:
                vkFormat = isSrgb ? org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SRGB : org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
                pixels = data.duplicate();
                pixels.position(0).limit(w * h * 4);
                break;
            case ABGR8:
                vkFormat = isSrgb ? org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SRGB : org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
                pixels = convertABGR8ToRGBA8(data, w * h);
                break;
            case RGB8:
                vkFormat = isSrgb ? org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SRGB : org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
                pixels = convert24BitToRGBA8(data, w * h, false);
                break;
            case BGR8:
                vkFormat = isSrgb ? org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SRGB : org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
                pixels = convert24BitToRGBA8(data, w * h, true);
                break;
            case Luminance8:
                vkFormat = isSrgb ? org.lwjgl.vulkan.VK10.VK_FORMAT_R8_SRGB : org.lwjgl.vulkan.VK10.VK_FORMAT_R8_UNORM;
                pixels = data.duplicate();
                pixels.position(0).limit(w * h);
                break;
            case Luminance8Alpha8:
                vkFormat = isSrgb ? org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8_SRGB : org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8_UNORM;
                pixels = data.duplicate();
                pixels.position(0).limit(w * h * 2);
                break;
            default:
                throw new UnsupportedOperationException("Vulkan backend does not support format: " + fmt);
        }

        VkTexture vkTex = rf.createTexture2DFromBuffer(pixels, w, h, vkFormat);
        textureCache.put(tex, vkTex);
        return vkTex;
    }

    private ByteBuffer convertABGR8ToRGBA8(ByteBuffer src, int pixelCount) {
        ByteBuffer dst = getConvertBuffer(pixelCount * 4);
        dst.clear();
        ByteBuffer s = src.duplicate();
        s.position(0);
        for (int i = 0; i < pixelCount; i++) {
            byte a = s.get();
            byte b = s.get();
            byte g = s.get();
            byte r = s.get();
            dst.put(r);
            dst.put(g);
            dst.put(b);
            dst.put(a);
        }
        dst.position(0).limit(pixelCount * 4);
        return dst;
    }

    private ByteBuffer convert24BitToRGBA8(ByteBuffer src, int pixelCount, boolean swapRB) {
        ByteBuffer dst = getConvertBuffer(pixelCount * 4);
        dst.clear();
        ByteBuffer s = src.duplicate();
        s.position(0);
        for (int i = 0; i < pixelCount; i++) {
            byte b1 = s.get();
            byte b2 = s.get();
            byte b3 = s.get();
            if (swapRB) {
                dst.put(b3);
                dst.put(b2);
                dst.put(b1);
            } else {
                dst.put(b1);
                dst.put(b2);
                dst.put(b3);
            }
            dst.put((byte) 0xFF);
        }
        dst.flip();
        return dst;
    }

    private ByteBuffer getConvertBuffer(int requiredBytes) {
        if (convertBuffer == null || convertBuffer.capacity() < requiredBytes) {
            convertBuffer = ByteBuffer.allocateDirect(requiredBytes);
        }
        return convertBuffer;
    }

    public void destroyCachedTextures() {
        if (textureCache.isEmpty()) {
            return;
        }
        for (VkTexture t : textureCache.values()) {
            if (t != null && t != whiteTex) {
                rf.destroyTexture(t);
            }
        }
        textureCache.clear();
        convertBuffer = null;
    }

    public void invalidate(Texture tex) {
        if (tex == null) {
            return;
        }
        VkTexture old = textureCache.remove(tex);
        if (old != null && old != whiteTex) {
            deferDestroyTexture(old);
        }
    }

    public void invalidateByImage(Image image) {
        if (image == null || textureCache.isEmpty()) {
            return;
        }
        textureCache.entrySet().removeIf(e -> {
            if (e.getKey().getImage() == image) {
                if (e.getValue() != whiteTex) {
                    deferDestroyTexture(e.getValue());
                }
                return true;
            }
            return false;
        });
    }

    private void deferDestroyTexture(VkTexture t) {
        if (t == null || t == whiteTex) {
            return;
        }
        if (deferredReleaseQueue == null || frameIndexSupplier == null) {
            rf.destroyTexture(t);
            return;
        }
        deferredReleaseQueue.enqueue(frameIndexSupplier.getAsInt(), () -> rf.destroyTexture(t));
    }
}
