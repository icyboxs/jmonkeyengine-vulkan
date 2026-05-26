package com.jme3.renderer.vulkan.resource;

import com.jme3.renderer.vulkan.context.VkContext;
import com.jme3.texture.Texture;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntSupplier; // 【新增导入】

import static org.lwjgl.vulkan.VK10.*;

public final class VulkanSamplerManager {

    private final VkContext vk;
    private final Map<VkSamplerKey, Long> samplerCache = new HashMap<>();
    private final IntSupplier defaultAnisoSupplier;

    //构造函数引入 Supplier 参数
    public VulkanSamplerManager(VkContext vk, IntSupplier defaultAnisoSupplier) {
        if (vk == null) {
            throw new IllegalArgumentException("vk is null");
        }
        this.vk = vk;
        this.defaultAnisoSupplier = defaultAnisoSupplier != null ? defaultAnisoSupplier : () -> 1;
    }

    public long getOrCreateSampler(Texture tex) {
        // 取出当前渲染环境配置的默认各向异性值
        int defaultAniso = defaultAnisoSupplier.getAsInt();

        VkSamplerKey key = new VkSamplerKey(tex, defaultAniso);
        Long cached = samplerCache.get(key);
        if (cached != null && cached.longValue() != 0L) {
            return cached.longValue();
        }

        // 将 key 计算后的安全 aniso 值传递给创建方法
        long sampler = createSamplerFromJme(tex, key.aniso);
        samplerCache.put(key, sampler);
        return sampler;
    }

    public void destroy() {
        if (vk.device() == null) {
            samplerCache.clear();
            return;
        }

        for (Long s : samplerCache.values()) {
            if (s != null && s.longValue() != 0L) {
                vkDestroySampler(vk.device(), s.longValue(), null);
            }
        }
        samplerCache.clear();
    }

    //增加 anisoValue 参数
    private long createSamplerFromJme(Texture tex, int anisoValue) {
        Texture.MagFilter mag = tex != null ? tex.getMagFilter() : Texture.MagFilter.Bilinear;
        Texture.MinFilter min = tex != null ? tex.getMinFilter() : Texture.MinFilter.BilinearNoMipMaps;

        Texture.WrapMode ws = tex != null ? tex.getWrap(Texture.WrapAxis.S) : Texture.WrapMode.EdgeClamp;
        Texture.WrapMode wt = tex != null ? tex.getWrap(Texture.WrapAxis.T) : Texture.WrapMode.EdgeClamp;

        int vkMag = (mag == Texture.MagFilter.Nearest) ? VK_FILTER_NEAREST : VK_FILTER_LINEAR;
        boolean useMip = !(min == Texture.MinFilter.NearestNoMipMaps || min == Texture.MinFilter.BilinearNoMipMaps);
        int vkMin;
        switch (min) {
            case NearestNoMipMaps:
            case NearestNearestMipMap:
            case NearestLinearMipMap:
                vkMin = VK_FILTER_NEAREST;
                break;
            default:
                vkMin = VK_FILTER_LINEAR;
                break;
        }

        int mipMode;
        switch (min) {
            case BilinearNearestMipMap:
            case NearestNearestMipMap:
                mipMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
                break;
            default:
                mipMode = VK_SAMPLER_MIPMAP_MODE_LINEAR;
                break;
        }

        int aU = mapWrap(ws);
        int aV = mapWrap(wt);

        //开启判断和硬件极限防护
        float aniso = anisoValue;
        boolean enableAniso = aniso > 1.0f && vk.isSamplerAnisotropyEnabled();
        // 与设备支持的最大值进行截断防崩
        float maxAniso = enableAniso ? Math.min(aniso, vk.maxSamplerAnisotropy()) : 1.0f;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack)
                    .sType$Default()
                    .magFilter(vkMag)
                    .minFilter(vkMin)
                    .mipmapMode(mipMode)
                    .addressModeU(aU)
                    .addressModeV(aV)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .anisotropyEnable(enableAniso)
                    .maxAnisotropy(maxAniso) // 传入截断后的安全值
                    .borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK)
                    .unnormalizedCoordinates(false)
                    .compareEnable(false)
                    .compareOp(VK_COMPARE_OP_ALWAYS);

            if (!useMip) {
                sci.minLod(0.0f).maxLod(0.0f).mipLodBias(0.0f);
            } else {
                sci.minLod(0.0f).maxLod(12.0f).mipLodBias(0.0f);
            }

            LongBuffer p = stack.mallocLong(1);
            int err = vkCreateSampler(vk.device(), sci, null, p);
            if (err != VK_SUCCESS) {
                throw new RuntimeException("vkCreateSampler failed: " + err);
            }
            return p.get(0);
        }
    }

    private static int mapWrap(Texture.WrapMode w) {
        if (w == null) {
            return VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        }
        switch (w) {
            case Repeat:
                return VK_SAMPLER_ADDRESS_MODE_REPEAT;
            case MirroredRepeat:
                return VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT;
            case Clamp:
            case EdgeClamp:
                return VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            case BorderClamp:
                return VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER;
            default:
                return VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        }
    }
}
