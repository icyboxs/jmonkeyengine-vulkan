package com.jme3.renderer.vulkan.binding.plan;

import java.util.EnumMap;
import java.util.Map;

/**
 * 分层注册表（FrequencyLayerRegistry）。
 * * 核心作用：
 * 维护“更新频率 (UpdateFrequency)”与“具体配置 (FrequencyLayerConfig)”之间的映射关系。
 * 它定义了 Vulkan 描述符集 (Descriptor Set) 的分层绑定标准。
 * 默认提供了一套经典的 Vulkan 频率分层方案，同时也允许开发者通过代码进行自定义覆盖。
 * * @author icyboxs
 */
public final class FrequencyLayerRegistry {

    // 使用 EnumMap 来存储映射，因为键是枚举类型，EnumMap 在底层使用数组实现，查询速度极快且无哈希冲突开销
    private final EnumMap<UpdateFrequency, FrequencyLayerConfig> cfg =
            new EnumMap<>(UpdateFrequency.class);

    /**
     * 构造函数：初始化默认的 Vulkan 描述符集分层映射策略。
     * * 这里的默认策略遵循 Vulkan 官方推荐的最佳实践：
     * 按更新频率从低到高分配 Set 索引 (0, 1, 2...)。
     */
    public FrequencyLayerRegistry() {
        // 1. LOW (低频层) -> 映射到 Set 0
        // 通常用于：全局数据、摄像机矩阵 (View/Proj)、材质基础参数。
        // 缓存策略：PERSISTENT (持久化)，一旦创建并绑定，跨多个 DrawCall 甚至多帧都不轻易销毁。
        cfg.put(UpdateFrequency.LOW,
                new FrequencyLayerConfig(UpdateFrequency.LOW, 0, DescriptorCachePolicy.PERSISTENT));
        
        // 2. MEDIUM (中频层) -> 映射到 Set 1
        // 通常用于：按帧更新的数据、当前 Render Pass 的共享参数 (如环境光、阴影贴图)。
        // 缓存策略：PER_FRAME (按帧缓存)，每帧结束后统一回收重建。
        cfg.put(UpdateFrequency.MEDIUM,
                new FrequencyLayerConfig(UpdateFrequency.MEDIUM, 1, DescriptorCachePolicy.PER_FRAME));
        
        // 3. HIGH (高频层) -> 映射到 Set 2
        // 通常用于：每次 DrawCall 都会改变的数据 (如每个 3D 模型的 World Matrix、骨骼动画矩阵)。
        // 缓存策略：PER_FRAME (按帧缓存)，通常结合 Dynamic UBO 使用。
        cfg.put(UpdateFrequency.HIGH,
                new FrequencyLayerConfig(UpdateFrequency.HIGH, 2, DescriptorCachePolicy.PER_FRAME));
    }

    /**
     * 获取指定频率层级的配置信息。
     * @param f 更新频率枚举 (LOW, MEDIUM, HIGH)
     * @return 对应的层级配置 (包含目标 Set 索引和缓存策略)
     */
    public FrequencyLayerConfig get(UpdateFrequency f) {
        return cfg.get(f);
    }

    /**
     * 注册或覆盖一个频率层级配置。
     * * 允许引擎上层或开发者打破默认规则。例如，如果某个特殊管线需要把 HIGH 频率数据绑定到 Set 3，
     * 就可以通过此方法动态注入自定义的 FrequencyLayerConfig。
     * @param c 自定义的频率层级配置
     * @throws IllegalArgumentException 如果传入的配置为空
     */
    public void register(FrequencyLayerConfig c) {
        if (c == null) {
            throw new IllegalArgumentException("config is null");
        }
        cfg.put(c.frequency, c);
    }

    /**
     * 获取当前注册表配置的快照。
     * * 返回一个新的 EnumMap 实例，防止外部代码直接修改注册表的内部状态，
     * 保证了核心配置的数据安全性 (防御性编程)。
     * @return 包含当前所有映射关系的 Map 副本
     */
    public Map<UpdateFrequency, FrequencyLayerConfig> snapshot() {
        return new EnumMap<>(cfg);
    }
}