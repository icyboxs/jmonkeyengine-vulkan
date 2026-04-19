package com.jme3.renderer.vulkan.reflection;

import com.jme3.renderer.vulkan.VkDebugFlags;
import com.jme3.renderer.vulkan.shader.ShaderArtifact;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;

/**
 * S2-T3/S2-T4: 反射结果缓存 + 命中统计回调
 *
 * 说明：
 * - 主路径请使用 reflect(ShaderArtifact vert, ShaderArtifact frag)，会走真实 SPIR-V 反射。
 * - reflect(int vertHash, int fragHash) 仅为兼容旧调用，返回固定 fallback 结果。
 */
public final class VkReflectionManager {

    private static final Logger LOGGER = Logger.getLogger(VkReflectionManager.class.getName());

    private final SpirvReflector reflector = new SpirvReflector();

    public interface StatsSink {
        void onHit();
        void onMiss();
    }

    private StatsSink statsSink;

    private final Map<VkReflectionCacheKey, VkReflectionResult> cache = new HashMap<>();

    private long hitCount = 0;
    private long missCount = 0;

    public VkReflectionManager() {
    }

    public void setStatsSink(StatsSink statsSink) {
        this.statsSink = statsSink;
    }

    /**
     * 兼容旧接口：仅凭 hash 无法做真实反射，返回固定 fallback。
     */
    public VkReflectionResult reflect(int vertSpvHash, int fragSpvHash) {
        VkReflectionCacheKey key = new VkReflectionCacheKey(vertSpvHash, fragSpvHash);

        VkReflectionResult cached = cache.get(key);
        if (cached != null) {
            onCacheHit(key, VkDebugFlags.REFLECTION_COMPAT_VERBOSE_LOG);
            return cached;
        }

        onCacheMiss(key, VkDebugFlags.REFLECTION_COMPAT_VERBOSE_LOG);

        VkReflectionResult r = fallbackResult(vertSpvHash, fragSpvHash);

        logReflectionResult(r);

        cache.put(key, r);
        LOGGER.info("[Reflect] cached key=" + key + " result=" + r);
        return r;
    }

    public long getHitCount() {
        return hitCount;
    }

    public long getMissCount() {
        return missCount;
    }

    public int cacheSize() {
        return cache.size();
    }

    public void clear() {
        cache.clear();
        hitCount = 0;
        missCount = 0;
    }

    /**
     * 主路径：真实反射（SpirvReflector）。
     */
    public VkReflectionResult reflect(ShaderArtifact vert, ShaderArtifact frag) {
        int vHash = (vert != null) ? vert.sourceHash : 0;
        int fHash = (frag != null) ? frag.sourceHash : 0;

        VkReflectionCacheKey key = new VkReflectionCacheKey(vHash, fHash);

        VkReflectionResult cached = cache.get(key);
        if (cached != null) {
            onCacheHit(key, false);
            return cached;
        }

        onCacheMiss(key, false);

        VkReflectionResult r = reflector.reflect(
                vert != null ? vert.spirv : null,
                frag != null ? frag.spirv : null,
                vHash,
                fHash
        );

        cache.put(key, r);
        return r;
    }

    // ---------------- internal ----------------

    private void onCacheHit(VkReflectionCacheKey key, boolean verboseLog) {
        hitCount++;
        if (statsSink != null) {
            statsSink.onHit();
        }
        if (verboseLog) {
            LOGGER.info("[Reflect] cache HIT key=" + key);
        }
    }

    private void onCacheMiss(VkReflectionCacheKey key, boolean verboseLog) {
        missCount++;
        if (statsSink != null) {
            statsSink.onMiss();
        }
        if (verboseLog) {
            LOGGER.info("[Reflect] cache MISS key=" + key);
        }
    }

    private static void logReflectionResult(VkReflectionResult r) {
        for (VkReflectionResult.DescriptorBinding b : r.descriptorBindings) {
            LOGGER.info("[Reflect][SetBinding] set=" + b.set
                    + " binding=" + b.binding
                    + " type=" + b.type
                    + " stageFlags=" + b.stageFlags
                    + " name=" + b.name);
        }
        for (VkReflectionResult.UboMember m : r.uboMembers) {
            LOGGER.info("[Reflect][UBO] block=" + m.blockName
                    + " member=" + m.memberName
                    + " offset=" + m.offset
                    + " size=" + m.size);
        }
        for (VkPushConstantRangeInfo pc : r.pushConstantRanges) {
            LOGGER.info("[Reflect][PushConst] offset=" + pc.offset
                    + " size=" + pc.size
                    + " stageFlags=" + pc.stageFlags);
        }
    }

    private static VkReflectionResult fallbackResult(int vertHash, int fragHash) {
        ArrayList<VkReflectionResult.DescriptorBinding> bs = new ArrayList<>();
        ArrayList<VkPushConstantRangeInfo> pcs = new ArrayList<>();

        // set0 baseline
        bs.add(new VkReflectionResult.DescriptorBinding(
                0, 0, "UNIFORM_BUFFER_DYNAMIC",
                VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                "PerDraw"
        ));
        bs.add(new VkReflectionResult.DescriptorBinding(
                0, 1, "COMBINED_IMAGE_SAMPLER",
                VK_SHADER_STAGE_FRAGMENT_BIT,
                "ColorMap"
        ));
        bs.add(new VkReflectionResult.DescriptorBinding(
                0, 2, "COMBINED_IMAGE_SAMPLER",
                VK_SHADER_STAGE_FRAGMENT_BIT,
                "LightMap"
        ));

        // set1 ExtraTex
        bs.add(new VkReflectionResult.DescriptorBinding(
                1, 0, "COMBINED_IMAGE_SAMPLER",
                VK_SHADER_STAGE_FRAGMENT_BIT,
                "ExtraTex"
        ));

        // 最小 push constant range：16 bytes
        pcs.add(new VkPushConstantRangeInfo(
                0,
                16,
                VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT
        ));

        return new VkReflectionResult(
                vertHash,
                fragHash,
                bs,
                Collections.emptyList(),
                pcs
        );
    }
}
