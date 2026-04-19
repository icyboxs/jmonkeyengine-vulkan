package com.jme3.renderer.vulkan.reflection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * S3-T1: 用于描述 PipelineLayout 结构签名，作为 layout cache key。
 *
 * 当前最小模型： - descriptor bindings（set/binding/type/stage） - push constant
 * ranges（offset/size/stage）
 *
 * 后续可继续扩展： - descriptorCount - immutable samplers hash - set 数量约束等
 */
public final class VkPipelineLayoutSignature {

    public final List<BindingSig> bindings;
    public final List<PushConstantSig> pushConstants;

    public VkPipelineLayoutSignature(List<BindingSig> bindings,
            List<PushConstantSig> pushConstants) {
        this.bindings = immutableSortedBindings(bindings);
        this.pushConstants = immutableSortedPush(pushConstants);
    }

    public static VkPipelineLayoutSignature empty() {
        return new VkPipelineLayoutSignature(
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    /**
     * 从反射结果构造签名（当前仅吃 descriptorBindings，push constants 暂空）。
     */
    public static VkPipelineLayoutSignature fromReflection(VkReflectionResult r) {
        if (r == null) {
            return empty();
        }

        List<BindingSig> bs = new ArrayList<>(r.descriptorBindings.size());
        for (VkReflectionResult.DescriptorBinding b : r.descriptorBindings) {
            bs.add(new BindingSig(
                    b.set,
                    b.binding,
                    safeType(b.type),
                    b.stageFlags
            ));
        }

        List<PushConstantSig> pcs = new ArrayList<>();
        if (r.pushConstantRanges != null) {
            for (VkPushConstantRangeInfo pc : r.pushConstantRanges) {
                if (pc == null) {
                    continue;
                }
                pcs.add(new PushConstantSig(
                        pc.offset,
                        pc.size,
                        pc.stageFlags
                ));
            }
        }

        return new VkPipelineLayoutSignature(bs, pcs);
    }

    private static String safeType(String t) {
        return t != null ? t : "UNKNOWN";
    }

    private static List<BindingSig> immutableSortedBindings(List<BindingSig> in) {
        if (in == null || in.isEmpty()) {
            return Collections.emptyList();
        }
        ArrayList<BindingSig> copy = new ArrayList<>(in);
        copy.sort((a, b) -> {
            int c = Integer.compare(a.set, b.set);
            if (c != 0) {
                return c;
            }
            c = Integer.compare(a.binding, b.binding);
            if (c != 0) {
                return c;
            }
            c = a.type.compareTo(b.type);
            if (c != 0) {
                return c;
            }
            return Integer.compare(a.stageFlags, b.stageFlags);
        });
        return Collections.unmodifiableList(copy);
    }

    private static List<PushConstantSig> immutableSortedPush(List<PushConstantSig> in) {
        if (in == null || in.isEmpty()) {
            return Collections.emptyList();
        }
        ArrayList<PushConstantSig> copy = new ArrayList<>(in);
        copy.sort((a, b) -> {
            int c = Integer.compare(a.offset, b.offset);
            if (c != 0) {
                return c;
            }
            c = Integer.compare(a.size, b.size);
            if (c != 0) {
                return c;
            }
            return Integer.compare(a.stageFlags, b.stageFlags);
        });
        return Collections.unmodifiableList(copy);
    }

    public static final class BindingSig {

        public final int set;
        public final int binding;
        public final String type;
        public final int stageFlags;

        public BindingSig(int set, int binding, String type, int stageFlags) {
            this.set = set;
            this.binding = binding;
            this.type = type;
            this.stageFlags = stageFlags;
        }

        @Override
        public String toString() {
            return "BindingSig{set=" + set
                    + ", binding=" + binding
                    + ", type='" + type + '\''
                    + ", stageFlags=" + stageFlags + '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof BindingSig)) {
                return false;
            }
            BindingSig that = (BindingSig) o;
            return set == that.set
                    && binding == that.binding
                    && stageFlags == that.stageFlags
                    && Objects.equals(type, that.type);
        }

        @Override
        public int hashCode() {
            return Objects.hash(set, binding, type, stageFlags);
        }
    }

    public static final class PushConstantSig {

        public final int offset;
        public final int size;
        public final int stageFlags;

        public PushConstantSig(int offset, int size, int stageFlags) {
            this.offset = offset;
            this.size = size;
            this.stageFlags = stageFlags;
        }

        @Override
        public String toString() {
            return "PushConstantSig{offset=" + offset
                    + ", size=" + size
                    + ", stageFlags=" + stageFlags + '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof PushConstantSig)) {
                return false;
            }
            PushConstantSig that = (PushConstantSig) o;
            return offset == that.offset
                    && size == that.size
                    && stageFlags == that.stageFlags;
        }

        @Override
        public int hashCode() {
            return Objects.hash(offset, size, stageFlags);
        }
    }

    @Override
    public String toString() {
        return "VkPipelineLayoutSignature{bindings=" + bindings.size()
                + ", pushConstants=" + pushConstants.size()
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof VkPipelineLayoutSignature)) {
            return false;
        }
        VkPipelineLayoutSignature that = (VkPipelineLayoutSignature) o;
        return Objects.equals(bindings, that.bindings)
                && Objects.equals(pushConstants, that.pushConstants);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bindings, pushConstants);
    }
}
