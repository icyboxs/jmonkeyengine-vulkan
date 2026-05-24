package com.jme3.renderer.vulkan.pipeline;

import com.jme3.material.RenderState;
import com.jme3.scene.VertexBuffer;

import java.util.Objects;
import static org.lwjgl.vulkan.VK10.*;

public final class VkPipelineKey {

    // =======================================================
    // 【新增】全动态顶点位置映射契约 (Location 0 ~ 7)
    // 规定了 JME3 的 VertexBuffer 类型在 Shader 中对应的 Location 索引。
    // 例如：以后在 Shader 里写法线(Normal)必须用 layout(location=3) in vec3 inNormal;
    // =======================================================
    public static final VertexBuffer.Type[] VERTEX_TYPES = {
        VertexBuffer.Type.Position, // Location 0
        VertexBuffer.Type.TexCoord, // Location 1
        VertexBuffer.Type.Color, // Location 2
        VertexBuffer.Type.Normal, // Location 3
        VertexBuffer.Type.Tangent, // Location 4
        VertexBuffer.Type.BoneWeight,// Location 5
        VertexBuffer.Type.BoneIndex, // Location 6
        VertexBuffer.Type.TexCoord2 // Location 7
    };

    public final VkShaderKey shaderKey;
    public final PassKey passKey;

    public final int cullMode;
    public final boolean depthTest;
    public final boolean depthWrite;
    public final int depthCompareOp;
    public final Blend blend;

    // 【新增】保存网格顶点特征的变体掩码
    public final int vertexLayoutMask;

    public enum Blend {
        Off,
        Alpha,
        Additive
    }

    public VkPipelineKey(VkShaderKey shaderKey,
            PassKey passKey,
            int cullMode,
            boolean depthTest,
            boolean depthWrite,
            int depthCompareOp,
            Blend blend,
            int vertexLayoutMask) {
        this.shaderKey = shaderKey;
        this.passKey = passKey;
        this.cullMode = cullMode;
        this.depthTest = depthTest;
        this.depthWrite = depthWrite;
        this.depthCompareOp = depthCompareOp;
        this.blend = blend;
        this.vertexLayoutMask = vertexLayoutMask;
    }

    @Override
    public int hashCode() {
        // 【纯数值运算】避免隐式 new Object[] 和基础类型 Autoboxing
        int result = shaderKey != null ? shaderKey.hashCode() : 0;
        result = 31 * result + (passKey != null ? passKey.hashCode() : 0);
        result = 31 * result + cullMode;
        result = 31 * result + (depthTest ? 1 : 0);
        result = 31 * result + (depthWrite ? 1 : 0);
        result = 31 * result + depthCompareOp;
        result = 31 * result + (blend != null ? blend.ordinal() : 0);
        result = 31 * result + vertexLayoutMask;
        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof VkPipelineKey)) {
            return false;
        }
        VkPipelineKey k = (VkPipelineKey) o;
        return cullMode == k.cullMode
                && depthTest == k.depthTest
                && depthWrite == k.depthWrite
                && depthCompareOp == k.depthCompareOp
                && blend == k.blend
                && vertexLayoutMask == k.vertexLayoutMask
                && Objects.equals(shaderKey, k.shaderKey)
                && Objects.equals(passKey, k.passKey);
    }

    public static VkPipelineKey base() {
        return new VkPipelineKey(
                new VkShaderKey(0, 0, new VkVariantKey(false, false, false)),
                null,
                VK_CULL_MODE_NONE,
                true,
                true,
                VK_COMPARE_OP_LESS_OR_EQUAL,
                Blend.Off,
                0 // 默认没有掩码
        );
    }

    public static VkPipelineKey fromHashes(int vertHash,
            int fragHash,
            RenderState rs,
            VkVariantKey variant,
            PassKey passKey,
            int vertexLayoutMask) {

        VkShaderKey sk = new VkShaderKey(vertHash, fragHash, variant);

        if (rs == null) {
            return new VkPipelineKey(
                    sk, passKey, VK_CULL_MODE_NONE, true, true, VK_COMPARE_OP_LESS_OR_EQUAL, Blend.Off, vertexLayoutMask
            );
        }

        int vkCull = VK_CULL_MODE_BACK_BIT;
        if (rs.getFaceCullMode() != null) {
            switch (rs.getFaceCullMode()) {
                case Off:
                    vkCull = VK_CULL_MODE_NONE;
                    break;
                case Back:
                    vkCull = VK_CULL_MODE_BACK_BIT;
                    break;
                case Front:
                    vkCull = VK_CULL_MODE_FRONT_BIT;
                    break;
                case FrontAndBack:
                    vkCull = VK_CULL_MODE_FRONT_BIT | VK_CULL_MODE_BACK_BIT;
                    break;
                default:
                    vkCull = VK_CULL_MODE_BACK_BIT;
                    break;
            }
        }

        boolean depthTest = rs.isDepthTest();
        boolean depthWrite = rs.isDepthWrite();

        int compareOp = VK_COMPARE_OP_LESS_OR_EQUAL;
        if (rs.getDepthFunc() != null) {
            switch (rs.getDepthFunc()) {
                case Never:
                    compareOp = VK_COMPARE_OP_NEVER;
                    break;
                case Less:
                    compareOp = VK_COMPARE_OP_LESS;
                    break;
                case LessOrEqual:
                    compareOp = VK_COMPARE_OP_LESS_OR_EQUAL;
                    break;
                case Greater:
                    compareOp = VK_COMPARE_OP_GREATER;
                    break;
                case GreaterOrEqual:
                    compareOp = VK_COMPARE_OP_GREATER_OR_EQUAL;
                    break;
                case Equal:
                    compareOp = VK_COMPARE_OP_EQUAL;
                    break;
                case NotEqual:
                    compareOp = VK_COMPARE_OP_NOT_EQUAL;
                    break;
                case Always:
                    compareOp = VK_COMPARE_OP_ALWAYS;
                    break;
                default:
                    compareOp = VK_COMPARE_OP_LESS_OR_EQUAL;
                    break;
            }
        }

        Blend blend = Blend.Off;
        if (rs.getBlendMode() != null) {
            switch (rs.getBlendMode()) {
                case Alpha:
                case PremultAlpha:
                case AlphaSumA:
                    blend = Blend.Alpha;
                    break;
                case Additive:
                case AlphaAdditive:
                    blend = Blend.Additive;
                    break;
                default:
                    blend = Blend.Off;
                    break;
            }
        }

        return new VkPipelineKey(sk, passKey, vkCull, depthTest, depthWrite, compareOp, blend, vertexLayoutMask);
    }
}
