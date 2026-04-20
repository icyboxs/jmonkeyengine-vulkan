package com.jme3.renderer.vulkan.binding.cache;

import java.util.Objects;

/**
 * 对象级 HIGH 缓存键（D2 最小版）
 * @author icyboxs
 */
public final class ObjectBindingKey {
    public final int frameIndex;
    public final int setIndex;
    public final long setLayout;

    // 先用 materialHash 近似 objectId，后续可替换真实 geometry/object id
    public final int objectId;

    public final int pipelineHash;
    public final int extraTexId;

    public final long perDrawUboHandle;
    public final int perDrawUboRange;
    public final int dynamicOffset;

    public ObjectBindingKey(int frameIndex,
                            int setIndex,
                            long setLayout,
                            int objectId,
                            int pipelineHash,
                            int extraTexId,
                            long perDrawUboHandle,
                            int perDrawUboRange,
                            int dynamicOffset) {
        this.frameIndex = frameIndex;
        this.setIndex = setIndex;
        this.setLayout = setLayout;
        this.objectId = objectId;
        this.pipelineHash = pipelineHash;
        this.extraTexId = extraTexId;
        this.perDrawUboHandle = perDrawUboHandle;
        this.perDrawUboRange = perDrawUboRange;
        this.dynamicOffset = dynamicOffset;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ObjectBindingKey)) return false;
        ObjectBindingKey k = (ObjectBindingKey) o;
        return frameIndex == k.frameIndex
                && setIndex == k.setIndex
                && setLayout == k.setLayout
                && objectId == k.objectId
                && pipelineHash == k.pipelineHash
                && extraTexId == k.extraTexId
                && perDrawUboHandle == k.perDrawUboHandle
                && perDrawUboRange == k.perDrawUboRange
                && dynamicOffset == k.dynamicOffset;
    }

    @Override
    public int hashCode() {
        return Objects.hash(frameIndex, setIndex, setLayout, objectId,
                pipelineHash, extraTexId, perDrawUboHandle, perDrawUboRange, dynamicOffset);
    }
}
