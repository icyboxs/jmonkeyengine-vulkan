package com.jme3.renderer.vulkan.binding.plan;

/**
 * 描述符集更新频率层级的配置类。
 * * 作用：
 * 将一个抽象的“更新频率（UpdateFrequency）”映射到具体的 Vulkan 物理资源：
 * 1. 目标 Descriptor Set 的索引。
 * 2. 该 Set 应该采取的生命周期缓存策略。
 * * 这是一个不可变（Immutable）的数据类，确保配置一旦创建就无法被意外篡改。
 * * @author icyboxs
 */
public final class FrequencyLayerConfig {
    
    /**
     * 该配置对应的更新频率层级（如 LOW: 低频, MEDIUM: 中频, HIGH: 高频）。
     * 用于在着色器反射和描述符绑定时进行分类。
     */
    public final UpdateFrequency frequency;
    
    /**
     * 绑定的 Vulkan 描述符集索引（Descriptor Set Index）。
     * 对应 GLSL 着色器中的 `layout(set = X, ...)` 中的 X 值。
     * 例如，通常 LOW 映射到 Set 0，HIGH 映射到 Set 2。
     */
    public final int setIndex;
    
    /**
     * 该层级描述符集的缓存与回收策略。
     * 决定了分配出来的 Descriptor Set 句柄存活多久：
     * - PERSISTENT: 跨帧持久存在（如材质基础贴图，直到材质被销毁才释放）。
     * - PER_FRAME: 按帧存活（如当前帧的摄像机矩阵，这一帧渲染完后，下一帧直接重置回收）。
     * - NONE: 不缓存，每次用到都重新分配和写入。
     */
    public final DescriptorCachePolicy cachePolicy;

    /**
     * 构造并初始化一个频率层级配置。
     * 内部包含了严格的参数校验，防止传入非法的空值或负数索引引发 Vulkan 底层崩溃。
     * * @param frequency   更新频率标识
     * @param setIndex    对应的 Vulkan Set 索引 (必须 >= 0)
     * @param cachePolicy 缓存策略
     */
    public FrequencyLayerConfig(UpdateFrequency frequency, int setIndex, DescriptorCachePolicy cachePolicy) {
        if (frequency == null) throw new IllegalArgumentException("frequency is null");
        if (setIndex < 0) throw new IllegalArgumentException("setIndex < 0");
        if (cachePolicy == null) throw new IllegalArgumentException("cachePolicy is null");
        
        this.frequency = frequency;
        this.setIndex = setIndex;
        this.cachePolicy = cachePolicy;
    }
}