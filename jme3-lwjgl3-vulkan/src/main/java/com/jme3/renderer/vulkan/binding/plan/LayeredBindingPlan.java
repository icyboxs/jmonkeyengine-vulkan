package com.jme3.renderer.vulkan.binding.plan;

/**
 * 分层绑定计划 (Layered Binding Plan)。
 * * 作用：
 * 这是一个不可变的数据传输对象 (DTO)，代表了当前 Vulkan 管线最终确定的描述符集 (Descriptor Set) 分布方案。
 * 它从 FrequencyLayerRegistry 提取配置，明确了 Low、Medium、High 三个频率层级分别占用哪个 Set 槽位。
 * * 渲染器在执行 Bind 操作时，会直接读取这个计划，从而知道把不同生命周期的资源绑定到哪个 Set 上。
 * * @author icyboxs
 */
public final class LayeredBindingPlan {
    
    /** 低频更新层 (如全局材质、全局相机矩阵) 对应的 Set 索引。通常为 0。 */
    public final int lowSetIndex;
    
    /** 中频更新层 (如按帧更新的数据、Pass共享参数) 对应的 Set 索引。通常为 1。 */
    public final int mediumSetIndex;
    
    /** * 高频更新层 (如每个 DrawCall 变动的模型矩阵) 对应的 Set 索引。通常为 2。
     * 可为 -1，表示当前管线未启用高频层 (可能是通过 Push Constants 替代了高频 UBO)。
     */
    public final int highSetIndex;

    /**
     * 内部构造函数。
     * 包含了严格的防呆校验，防止底层 Vulkan API 收到非法的 Set 索引而崩溃。
     */
    public LayeredBindingPlan(int low, int medium, int high) {
        if (low < 0) throw new IllegalArgumentException("low < 0");
        if (medium < 0) throw new IllegalArgumentException("medium < 0");
        if (high < -1) throw new IllegalArgumentException("high < -1");
        
        this.lowSetIndex = low;
        this.mediumSetIndex = medium;
        this.highSetIndex = high;
    }

    /**
     * 静态工厂方法：从分层注册表 (FrequencyLayerRegistry) 中生成绑定计划。
     * * 业务逻辑约束：
     * - LOW (低频) 和 MEDIUM (中频) 层级是渲染管线的基础，必须存在。
     * - HIGH (高频) 层级是可选的，如果注册表中没有配置，则默认为 -1 (未启用)。
     * * @param r 包含了层级配置的注册表
     * @return 最终确定的分层绑定计划
     */
    public static LayeredBindingPlan from(FrequencyLayerRegistry r) {
        if (r == null) throw new IllegalArgumentException("registry is null");

        // 提取各个层级的配置
        FrequencyLayerConfig low = r.get(UpdateFrequency.LOW);
        FrequencyLayerConfig med = r.get(UpdateFrequency.MEDIUM);
        FrequencyLayerConfig high = r.get(UpdateFrequency.HIGH);

        // 强制约束：管线必须至少有 LOW 和 MEDIUM 两个基础层级
        if (low == null) throw new IllegalStateException("LOW config missing");
        if (med == null) throw new IllegalStateException("MEDIUM config missing");

        // 高频层级允许为空，为空时索引标记为 -1
        int h = (high != null) ? high.setIndex : -1;
        
        return new LayeredBindingPlan(low.setIndex, med.setIndex, h);
    }
}