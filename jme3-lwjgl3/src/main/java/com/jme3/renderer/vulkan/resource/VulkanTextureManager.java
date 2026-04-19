package com.jme3.renderer.vulkan.resource;

import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.IntSupplier;
import static org.lwjgl.vulkan.VK10.*;

public final class VulkanTextureManager {

    private final VulkanDeferredReleaseQueue deferredReleaseQueue;
    private final IntSupplier frameIndexSupplier;
    private final VkResourceFactory rf;
    private final Map<Texture, VkTexture> textureCache = new WeakHashMap<>();
    private VkTexture whiteTex;

    // 优化：复用转换缓冲，避免频繁分配 DirectByteBuffer
    private ByteBuffer convertBuffer;

    public VulkanTextureManager(VkResourceFactory rf, VkTexture whiteTex,
            VulkanDeferredReleaseQueue deferredReleaseQueue,
            IntSupplier frameIndexSupplier) {
        if (rf == null) {
            throw new IllegalArgumentException("rf is null");
        }
        this.rf = rf;
        this.whiteTex = whiteTex;
        this.deferredReleaseQueue = deferredReleaseQueue;
        this.frameIndexSupplier = frameIndexSupplier;
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
        if (data == null) {
            return whiteTex;
        }

        int vkFormat;
        ByteBuffer pixels;

        // --- 核心：处理 jME3 到 Vulkan 的格式映射与转换 ---
        Image.Format fmt = img.getFormat();
        switch (fmt) {
            case RGBA8:
                vkFormat = VK_FORMAT_R8G8B8A8_UNORM;
                pixels = data.duplicate();
                pixels.position(0).limit(w * h * 4);
                break;

            case ABGR8:
                // jME 的 ABGR8 在内存通常是 A,B,G,R。为了修复发红，转为 R,G,B,A
                vkFormat = VK_FORMAT_R8G8B8A8_UNORM;
                pixels = convertABGR8ToRGBA8(data, w * h);
                break;

            case RGB8:
                // Vulkan 不原生支持 24位，补全 A 频道
                vkFormat = VK_FORMAT_R8G8B8A8_UNORM;
                pixels = convert24BitToRGBA8(data, w * h, false);
                break;

            case BGR8:
                // 解决发红：原数据 B,G,R -> 转为 R,G,B,A
                vkFormat = VK_FORMAT_R8G8B8A8_UNORM;
                pixels = convert24BitToRGBA8(data, w * h, true);
                break;

            case Luminance8:
                vkFormat = VK_FORMAT_R8_UNORM;
                pixels = data.duplicate();
                pixels.position(0).limit(w * h);
                break;

            case Luminance8Alpha8:
                vkFormat = VK_FORMAT_R8G8_UNORM;
                pixels = data.duplicate();
                pixels.position(0).limit(w * h * 2);
                break;

            default:
                throw new UnsupportedOperationException("Vulkan backend does not support format: " + fmt);
        }

        // 修改：假设 rf.createTexture2DFromBuffer 现在接受 vkFormat 参数
        VkTexture vkTex = rf.createTexture2DFromBuffer(pixels, w, h, vkFormat);
        textureCache.put(tex, vkTex);
        return vkTex;
    }

    // =======================================================
    // 转换算法块
    // =======================================================
    /**
     * 将 ABGR (A,B,G,R) 转换为 RGBA (R,G,B,A)
     */
    private ByteBuffer convertABGR8ToRGBA8(ByteBuffer src, int pixelCount) {
        ByteBuffer dst = getConvertBuffer(pixelCount * 4);
        dst.clear();

        // 显式设置 position 防止 duplicate 后的状态异常
        ByteBuffer s = src.duplicate();
        s.position(0);

        // 逐字节读取和写入，彻底规避 Little-Endian 导致的位运算错位问题
        for (int i = 0; i < pixelCount; i++) {
            byte a = s.get(); // 原始 Byte 0 (A)
            byte b = s.get(); // 原始 Byte 1 (B)
            byte g = s.get(); // 原始 Byte 2 (G)
            byte r = s.get(); // 原始 Byte 3 (R)

            // 转换为 Vulkan 期望的 R8G8B8A8 顺序
            dst.put(r);
            dst.put(g);
            dst.put(b);
            dst.put(a);
        }

        dst.position(0).limit(pixelCount * 4);
        return dst;
    }

    /**
     * 将 24bit (RGB 或 BGR) 转换为 32bit RGBA
     */
    private ByteBuffer convert24BitToRGBA8(ByteBuffer src, int pixelCount, boolean swapRB) {
        ByteBuffer dst = getConvertBuffer(pixelCount * 4);
        dst.clear();

        ByteBuffer s = src.duplicate();
        s.position(0);

        for (int i = 0; i < pixelCount; i++) {
            byte b1 = s.get(); // R (if RGB) or B (if BGR)
            byte b2 = s.get(); // G
            byte b3 = s.get(); // B (if RGB) or R (if BGR)

            if (swapRB) {
                dst.put(b3); // R
                dst.put(b2); // G
                dst.put(b1); // B
            } else {
                dst.put(b1); // R
                dst.put(b2); // G
                dst.put(b3); // B
            }
            dst.put((byte) 0xFF); // Alpha = 255
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
