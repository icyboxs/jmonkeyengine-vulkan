package com.jme3.renderer.vulkan.reflection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class VkReflectionResult {

    public final int vertSpvHash;
    public final int fragSpvHash;
    public final int compSpvHash;

    public final List<DescriptorBinding> descriptorBindings;
    public final List<VkPushConstantRangeInfo> pushConstantRanges;
    public final List<UboMember> uboMembers;

    public VkReflectionResult(int vertSpvHash,
            int fragSpvHash,
            int compSpvHash,
            List<DescriptorBinding> descriptorBindings,
            List<UboMember> uboMembers,
            List<VkPushConstantRangeInfo> pushConstantRanges) {
        this.vertSpvHash = vertSpvHash;
        this.fragSpvHash = fragSpvHash;
        this.compSpvHash = compSpvHash;
        this.descriptorBindings = immutableCopy(descriptorBindings);
        this.uboMembers = immutableCopy(uboMembers);
        this.pushConstantRanges = immutableCopy(pushConstantRanges);
    }

    public static VkReflectionResult empty(int vertSpvHash, int fragSpvHash) {
        return new VkReflectionResult(
                vertSpvHash,
                fragSpvHash,
                0,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    private static <T> List<T> immutableCopy(List<T> in) {
        if (in == null || in.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(in));
    }

    public static final class DescriptorBinding {

        public final int set;
        public final int binding;
        public final String type;
        public final int stageFlags;
        public final String name;

        public DescriptorBinding(int set, int binding, String type, int stageFlags, String name) {
            this.set = set;
            this.binding = binding;
            this.type = type;
            this.stageFlags = stageFlags;
            this.name = name;
        }

        @Override
        public String toString() {
            return "DescriptorBinding{set=" + set
                    + ", binding=" + binding
                    + ", type='" + type + '\''
                    + ", stageFlags=" + stageFlags
                    + ", name='" + name + "'}";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof DescriptorBinding)) {
                return false;
            }
            DescriptorBinding that = (DescriptorBinding) o;
            return set == that.set
                    && binding == that.binding
                    && stageFlags == that.stageFlags
                    && Objects.equals(type, that.type)
                    && Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(set, binding, type, stageFlags, name);
        }
    }

    public static final class UboMember {

        public final String blockName;   
        public final String memberName;  
        public final int offset;         
        public final int size;           

        public UboMember(String blockName, String memberName, int offset, int size) {
            this.blockName = blockName;
            this.memberName = memberName;
            this.offset = offset;
            this.size = size;
        }

        @Override
        public String toString() {
            return "UboMember{block='" + blockName + '\''
                    + ", member='" + memberName + '\''
                    + ", offset=" + offset
                    + ", size=" + size + "}";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof UboMember)) {
                return false;
            }
            UboMember that = (UboMember) o;
            return offset == that.offset
                    && size == that.size
                    && Objects.equals(blockName, that.blockName)
                    && Objects.equals(memberName, that.memberName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(blockName, memberName, offset, size);
        }
    }

    @Override
    public String toString() {
        return "VkReflectionResult{vertSpvHash=" + vertSpvHash
                + ", fragSpvHash=" + fragSpvHash
                + ", compSpvHash=" + compSpvHash
                + ", descriptorBindings=" + descriptorBindings.size()
                + ", uboMembers=" + uboMembers.size()
                + ", pushConstantRanges=" + pushConstantRanges.size()
                + '}';
    }
}