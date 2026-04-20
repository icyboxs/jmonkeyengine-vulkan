package com.jme3.renderer.vulkan.binding.api;

public interface HighFrequencySetProvider {

    HighSetResolveResult resolveSet(DescriptorBindRequest req, int setIndex, long setLayout);

    default void beginFrame(int frameIndex) {
    }

    default void cleanup() {
    }
}
