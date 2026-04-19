package com.jme3.renderer.vulkan.resource;

import com.jme3.renderer.vulkan.frame.VulkanFrameDriver;
import com.jme3.renderer.vulkan.runtime.VulkanRuntimeStats;
import com.jme3.texture.Texture;

import java.util.HashMap;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 去 ABI 版本：
 * - 仅负责“材质相关 set 的分配与缓存”
 * - 不负责 descriptor 写入（由 binder/plan-driven resolver 统一处理）
 */
public final class VulkanMaterialManager {

    // 复合Key：Material要素 + Pipeline具体的 Layout 句柄
    private static final class MaterialLayoutKey {
        public final VkMaterialKey matKey;
        public final long setLayout;

        public MaterialLayoutKey(VkMaterialKey matKey, long setLayout) {
            this.matKey = matKey;
            this.setLayout = setLayout;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MaterialLayoutKey)) return false;
            MaterialLayoutKey that = (MaterialLayoutKey) o;
            return setLayout == that.setLayout && Objects.equals(matKey, that.matKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(matKey, setLayout);
        }
    }

    private final VulkanMaterialDescriptors materialDesc;
    private final VulkanRuntimeStats stats;

    private final Function<Texture, Long> samplerResolver;
    private final Function<Texture, VkTexture> textureResolver;
    private final Supplier<Texture> offscreenTexSupplier;
    private final Supplier<VkTexture> whiteTexSupplier;

    private final boolean debugNoMaterialCache;
    private final HashMap<MaterialLayoutKey, long[]> materialSetCache = new HashMap<>();

    public VulkanMaterialManager(VulkanMaterialDescriptors materialDesc,
                                 VulkanRuntimeStats stats,
                                 Function<Texture, Long> samplerResolver,
                                 Function<Texture, VkTexture> textureResolver,
                                 Supplier<Texture> offscreenTexSupplier,
                                 Supplier<VkTexture> whiteTexSupplier,
                                 boolean debugNoMaterialCache) {
        this.materialDesc = materialDesc;
        this.stats = stats;
        this.samplerResolver = samplerResolver;
        this.textureResolver = textureResolver;
        this.offscreenTexSupplier = offscreenTexSupplier;
        this.whiteTexSupplier = whiteTexSupplier;
        this.debugNoMaterialCache = debugNoMaterialCache;
    }

    /**
     * 分配/缓存 descriptor set（不写 descriptor 内容）。
     * 写入动作由外部 plan-driven 路径统一执行。
     */
    public long getOrCreateMaterialSet(int frameIndex,
                                       Texture jmeTex0,
                                       Texture jmeLight,
                                       VkTexture vkTex0,
                                       VkTexture vkLight,
                                       long setLayout) {
        if (setLayout == 0L) {
            throw new IllegalArgumentException("setLayout is 0");
        }

        if (vkTex0 == null) vkTex0 = textureResolver.apply(null);
        if (vkLight == null) vkLight = textureResolver.apply(null);

        long samp0 = samplerResolver.apply(jmeTex0 != null ? jmeTex0 : offscreenTexSupplier.get());
        long sampL = samplerResolver.apply(jmeLight);

        VkTexture white = whiteTexSupplier.get();
        if (jmeTex0 == null && white != null) samp0 = white.sampler;
        if (jmeLight == null && white != null) sampL = white.sampler;

        VkMaterialKey baseKey = VkMaterialKey.of(vkTex0, samp0, vkLight, sampL);
        MaterialLayoutKey key = new MaterialLayoutKey(baseKey, setLayout);

        if (debugNoMaterialCache) {
            long ds = materialDesc.allocSet(setLayout);
            if (stats != null) {
                stats.materialSetAlloc++;
            }
            return ds;
        }

        long[] sets = materialSetCache.get(key);
        if (sets == null) {
            if (stats != null) stats.materialMiss++;

            sets = new long[VulkanFrameDriver.MAX_FRAMES_IN_FLIGHT];
            for (int i = 0; i < sets.length; i++) {
                long ds = materialDesc.allocSet(setLayout);
                if (ds == 0L) {
                    throw new IllegalStateException("allocSet returned 0 for setLayout=" + setLayout);
                }
                sets[i] = ds;
                if (stats != null) stats.materialSetAlloc++;
            }
            materialSetCache.put(key, sets);
        } else {
            if (stats != null) stats.materialHit++;
        }

        if (frameIndex < 0 || frameIndex >= sets.length) {
            throw new IllegalArgumentException("frameIndex out of range: " + frameIndex);
        }
        return sets[frameIndex];
    }

    public long getMaterialPoolGrowCount() {
        return materialDesc.getGrowCount();
    }

    public int cacheSize() {
        return materialSetCache.size();
    }

    public void destroy() {
        materialSetCache.clear();
    }
}
