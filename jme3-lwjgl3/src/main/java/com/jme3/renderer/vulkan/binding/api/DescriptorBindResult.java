package com.jme3.renderer.vulkan.binding.api;

public final class DescriptorBindResult {

    public final long[] sets;
    public final int[] setIndices; 
    public final int[] dynamicOffsets;
    public final int[] dynamicOffsetSetIndices;
    public final int firstSet;

    public DescriptorBindResult(int firstSet, long[] sets, int[] dynamicOffsets) {
        this(firstSet, sets, null, dynamicOffsets, null);
    }

    public DescriptorBindResult(int firstSet, long[] sets, int[] dynamicOffsets, int[] dynamicOffsetSetIndices) {
        this(firstSet, sets, null, dynamicOffsets, dynamicOffsetSetIndices);
    }

    public DescriptorBindResult(int firstSet, long[] sets, int[] setIndices, int[] dynamicOffsets, int[] dynamicOffsetSetIndices) {
        this.firstSet = firstSet;
        this.sets = (sets != null) ? sets : new long[0];
        this.setIndices = (setIndices != null) ? setIndices : new int[0];
        this.dynamicOffsets = (dynamicOffsets != null) ? dynamicOffsets : new int[0];
        this.dynamicOffsetSetIndices = (dynamicOffsetSetIndices != null) ? dynamicOffsetSetIndices : new int[0];

        if (this.setIndices.length != 0 && this.setIndices.length != this.sets.length) {
            throw new IllegalArgumentException("setIndices length != sets length");
        }
    }

    public static DescriptorBindResult ofSparse(int[] setIndices, long[] sets, int[] dynamicOffsets, int[] dynamicOffsetSetIndices) {
        return new DescriptorBindResult(0, sets, setIndices, dynamicOffsets, dynamicOffsetSetIndices);
    }

    public void validateForBind(int pipelineSetCount) {
        if (pipelineSetCount <= 0) throw new IllegalArgumentException("pipelineSetCount <= 0");
        if (sets.length == 0) throw new IllegalStateException("sets is empty");

        for (int i = 0; i < sets.length; i++) {
            if (sets[i] == 0L) throw new IllegalStateException("sets[" + i + "] is 0");
        }

        // 零分配优化：使用 Bitmask 而不是 boolean[]
        if (setIndices.length != 0) {
            int seenMask = 0;
            for (int i = 0; i < setIndices.length; i++) {
                int si = setIndices[i];
                if (si < 0 || si >= pipelineSetCount) throw new IllegalStateException("setIndices out of range: " + si);
                if ((seenMask & (1 << si)) != 0) throw new IllegalStateException("duplicate setIndices: " + si);
                seenMask |= (1 << si);
            }
        } else {
            if (firstSet < 0) throw new IllegalStateException("firstSet < 0");
            if (firstSet + sets.length > pipelineSetCount) throw new IllegalStateException("bind sets out of range");
        }

        if (dynamicOffsetSetIndices.length != 0 && dynamicOffsetSetIndices.length != dynamicOffsets.length) {
            throw new IllegalStateException("dynamicOffsetSetIndices length != dynamicOffsets length");
        }

        for (int i = 0; i < dynamicOffsetSetIndices.length; i++) {
            int s = dynamicOffsetSetIndices[i];
            if (s < 0 || s >= pipelineSetCount) throw new IllegalStateException("dynamicOffsetSetIndices out of range: " + s);
        }
    }
}
