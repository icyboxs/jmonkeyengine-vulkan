package com.jme3.renderer.vulkan.reflection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Runtime shader reflection result (Stage S1: data model only).
 *
 * 当前仅用于日志观察，不改变现有固定 ABI 行为。
 */
public final class VkReflectionResult {

    /**
     * 来自哪个 shader 对（便于日志定位）
     */
    public final int vertSpvHash;
    public final int fragSpvHash;

    /**
     * descriptor 绑定信息（set/binding/type/stage）
     */
    public final List<DescriptorBinding> descriptorBindings;

    public final List<VkPushConstantRangeInfo> pushConstantRanges;

    /**
     * UBO 成员偏移信息（先按 block+member 记录）
     */
    public final List<UboMember> uboMembers;

    public VkReflectionResult(int vertSpvHash,
            int fragSpvHash,
            List<DescriptorBinding> descriptorBindings,
            List<UboMember> uboMembers,
            List<VkPushConstantRangeInfo> pushConstantRanges) {
        this.vertSpvHash = vertSpvHash;
        this.fragSpvHash = fragSpvHash;
        this.descriptorBindings = immutableCopy(descriptorBindings);
        this.uboMembers = immutableCopy(uboMembers);
        this.pushConstantRanges = immutableCopy(pushConstantRanges);
    }

    public static VkReflectionResult empty(int vertSpvHash, int fragSpvHash) {
        return new VkReflectionResult(
                vertSpvHash,
                fragSpvHash,
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
        public final String type;      // e.g. UNIFORM_BUFFER, COMBINED_IMAGE_SAMPLER
        public final int stageFlags;   // Vulkan stage bitmask
        public final String name;      // 可选，可能为空

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

        public final String blockName;   // e.g. PerDraw
        public final String memberName;  // e.g. g_Time
        public final int offset;         // byte offset
        public final int size;           // byte size（未知可填 -1）

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
                + ", descriptorBindings=" + descriptorBindings.size()
                + ", uboMembers=" + uboMembers.size()
                + ", pushConstantRanges=" + pushConstantRanges.size()
                + '}';
    }

}
