package com.jme3.renderer.vulkan.reflection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 管线描述符绑定计划 (Pipeline Descriptor Binding Plan)。
 * * 作用：
 * 代表了一个特定着色器组合 (Vertex + Fragment) 完整的资源绑定蓝图。
 * 它是通过解析 SPIR-V 字节码反射生成的，指导渲染器在执行 DrawCall 时如何正确地分配和绑定资源。
 * * 设计特点：
 * 这是一个完全不可变 (Immutable) 的数据结构，保证了多线程渲染或缓存读取时的绝对安全。
 */
public final class PipelineDescriptorBindingPlan {

    /**
     * 该管线中所有 Descriptor Set 的绑定计划列表。
     * 例如：列表中可能包含 Set 0 的计划（存 UBO）和 Set 1 的计划（存纹理）。
     */
    public final List<SetBindingPlan> sets;
    
    /**
     * 动态绑定 (Dynamic Bindings) 的严格顺序列表。
     * * 【Vulkan 核心机制说明】：
     * 当你调用 `vkCmdBindDescriptorSets` 绑定包含 DYNAMIC_UBO 的 Set 时，
     * Vulkan 要求你提供一个平铺的 `dynamicOffsets` 数组。这个数组里的偏移量必须
     * 严格按照 "先 Set 索引升序，再 Binding 索引升序" 的规则排列。
     * * 在反射阶段预先计算好这个顺序列表，可以极大地避免在每帧高频的 DrawCall 循环中
     * 进行排序和遍历查找，从而压榨出极致的 CPU 性能。
     */
    public final List<DynamicBindingRef> dynamicBindingsInOrder;

    /**
     * 构造函数。
     * 内部使用了防御性拷贝 (Defensive Copy) 和不可变包装 (UnmodifiableList)，
     * 确保蓝图一旦创建，外部无法对其进行篡改。
     */
    public PipelineDescriptorBindingPlan(List<SetBindingPlan> sets,
                                         List<DynamicBindingRef> dynamicBindingsInOrder) {
        this.sets = sets == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(sets));
        this.dynamicBindingsInOrder = dynamicBindingsInOrder == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(dynamicBindingsInOrder));
    }

    /**
     * 静态工厂：返回一个空的绑定计划。
     * 通常用于 Fallback 兜底（例如着色器没有任何 Uniform 或贴图时），防止出现空指针异常。
     */
    public static PipelineDescriptorBindingPlan empty() {
        return new PipelineDescriptorBindingPlan(
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    /**
     * 快速检索指定 Set 索引的绑定计划。
     * * @param setIndex 要查找的 Descriptor Set 索引 (例如 0, 1, 2)
     * @return 对应的 SetBindingPlan，如果该 Set 在着色器中未声明，则返回 null
     */
    public SetBindingPlan getSetPlan(int setIndex) {
        // 由于通常一个 Pipeline 最多也就 3~4 个 Set，直接遍历的性能往往优于 HashMap 查找
        for (SetBindingPlan s : sets) {
            if (s != null && s.setIndex == setIndex) {
                return s;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "PipelineDescriptorBindingPlan{sets=" + sets
                + ", dynamicBindingsInOrder=" + dynamicBindingsInOrder + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PipelineDescriptorBindingPlan)) return false;
        PipelineDescriptorBindingPlan that = (PipelineDescriptorBindingPlan) o;
        return Objects.equals(sets, that.sets)
                && Objects.equals(dynamicBindingsInOrder, that.dynamicBindingsInOrder);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sets, dynamicBindingsInOrder);
    }
}