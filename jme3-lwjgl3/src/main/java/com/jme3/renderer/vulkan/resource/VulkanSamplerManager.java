package com.jme3.renderer.vulkan.resource;

import com.jme3.renderer.vulkan.context.VkContext;
import com.jme3.texture.Texture;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;

public final class VulkanSamplerManager {

    private final VkContext vk;
    private final Map<VkSamplerKey, Long> samplerCache = new HashMap<>();

    public VulkanSamplerManager(VkContext vk) {
        if (vk == null) {
            throw new IllegalArgumentException("vk is null");
        }
        this.vk = vk;
    }

    public long getOrCreateSampler(Texture tex) {
        VkSamplerKey key = new VkSamplerKey(tex);
        Long cached = samplerCache.get(key);
        if (cached != null && cached.longValue() != 0L) {
            return cached.longValue();
        }

        long sampler = createSamplerFromJme(tex);
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

    private long createSamplerFromJme(Texture tex) {
        Texture.MagFilter mag = tex != null ? tex.getMagFilter() : Texture.MagFilter.Bilinear;
        Texture.MinFilter min = tex != null ? tex.getMinFilter() : Texture.MinFilter.BilinearNoMipMaps;

        Texture.WrapMode ws = tex != null ? tex.getWrap(Texture.WrapAxis.S) : Texture.WrapMode.EdgeClamp;
        Texture.WrapMode wt = tex != null ? tex.getWrap(Texture.WrapAxis.T) : Texture.WrapMode.EdgeClamp;

        int vkMag = (mag == Texture.MagFilter.Nearest) ? VK_FILTER_NEAREST : VK_FILTER_LINEAR;

        boolean useMip = !(min == Texture.MinFilter.NearestNoMipMaps
                || min == Texture.MinFilter.BilinearNoMipMaps);

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

        float aniso = tex != null ? tex.getAnisotropicFilter() : 0;
        boolean enableAniso = aniso > 1.0f;

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
                    .maxAnisotropy(enableAniso ? Math.max(1.0f, aniso) : 1.0f)
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
