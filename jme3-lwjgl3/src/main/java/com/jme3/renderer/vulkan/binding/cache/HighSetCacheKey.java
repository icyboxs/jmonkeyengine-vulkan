package com.jme3.renderer.vulkan.binding.cache;

import java.util.Objects;

/**
 * HIGH 层 descriptor set 缓存键（对象键，避免 long hash 碰撞问题）
 * @author icyboxs
 */
public final class HighSetCacheKey {
    public final int frameIndex;
    public final int setIndex;
    public final long setLayout;

    public final int dynamicOffset;
    public final int pipelineHash;
    public final int materialHash;
    public final int extraTexId;

    public final long perDrawUboHandle;
    public final int perDrawUboRange;

    public HighSetCacheKey(int frameIndex,
                           int setIndex,
                           long setLayout,
                           int dynamicOffset,
                           int pipelineHash,
                           int materialHash,
                           int extraTexId,
                           long perDrawUboHandle,
                           int perDrawUboRange) {
        this.frameIndex = frameIndex;
        this.setIndex = setIndex;
        this.setLayout = setLayout;
        this.dynamicOffset = dynamicOffset;
        this.pipelineHash = pipelineHash;
        this.materialHash = materialHash;
        this.extraTexId = extraTexId;
        this.perDrawUboHandle = perDrawUboHandle;
        this.perDrawUboRange = perDrawUboRange;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HighSetCacheKey)) return false;
        HighSetCacheKey k = (HighSetCacheKey) o;
        return frameIndex == k.frameIndex
                && setIndex == k.setIndex
                && setLayout == k.setLayout
                && dynamicOffset == k.dynamicOffset
                && pipelineHash == k.pipelineHash
                && materialHash == k.materialHash
                && extraTexId == k.extraTexId
                && perDrawUboHandle == k.perDrawUboHandle
                && perDrawUboRange == k.perDrawUboRange;
    }

    @Override
    public int hashCode() {
        return Objects.hash(frameIndex, setIndex, setLayout,
                dynamicOffset, pipelineHash, materialHash, extraTexId,
                perDrawUboHandle, perDrawUboRange);
    }
}
