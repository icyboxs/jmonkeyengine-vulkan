package com.jme3.renderer.vulkan.binding.cache;

import java.util.HashMap;
import java.util.Map;

/**
 * 按 frameIndex 管理 descriptor set 缓存。
 * 典型用于 set1 这类 PER_FRAME 缓存资源。
 * @author icyboxs
 */
public final class FrameSetCache {

    // frameIndex -> (cacheKey -> descriptorSet)
    private final HashMap<Integer, HashMap<Long, Long>> cacheByFrame = new HashMap<>();

    public Long get(int frameIndex, long cacheKey) {
        HashMap<Long, Long> m = cacheByFrame.get(frameIndex);
        if (m == null) {
            return null;
        }
        return m.get(cacheKey);
    }

    public void put(int frameIndex, long cacheKey, long descriptorSet) {
        if (descriptorSet == 0L) {
            return;
        }
        HashMap<Long, Long> m = cacheByFrame.computeIfAbsent(frameIndex, k -> new HashMap<>());
        m.put(cacheKey, descriptorSet);
    }

    public int sizeOfFrame(int frameIndex) {
        HashMap<Long, Long> m = cacheByFrame.get(frameIndex);
        return m != null ? m.size() : 0;
    }

    public int frameCount() {
        return cacheByFrame.size();
    }

    public void clearFrame(int frameIndex) {
        HashMap<Long, Long> m = cacheByFrame.get(frameIndex);
        if (m != null) {
            m.clear();
        }
    }

    public void clearAll() {
        for (Map<Long, Long> m : cacheByFrame.values()) {
            m.clear();
        }
        cacheByFrame.clear();
    }
}
