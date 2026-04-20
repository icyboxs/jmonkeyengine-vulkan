package com.jme3.renderer.vulkan.pipeline;

import com.jme3.material.RenderState;
import java.util.Objects;
import static org.lwjgl.vulkan.VK10.*;

public final class VkPipelineKey {

    public final VkShaderKey shaderKey;
    public final PassKey passKey;

    public final int cullMode;
    public final boolean depthTest;
    public final boolean depthWrite;
    public final int depthCompareOp;
    public final Blend blend;

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
            Blend blend) {
        this.shaderKey = shaderKey;
        this.passKey = passKey;
        this.cullMode = cullMode;
        this.depthTest = depthTest;
        this.depthWrite = depthWrite;
        this.depthCompareOp = depthCompareOp;
        this.blend = blend;
    }

    

    @Override
    public int hashCode() {
        return Objects.hash(shaderKey, passKey, cullMode, depthTest, depthWrite, depthCompareOp, blend);
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
                Blend.Off
        );
    }

    public static VkPipelineKey fromHashes(int vertHash,
            int fragHash,
            RenderState rs,
            VkVariantKey variant,
            PassKey passKey) {

        VkShaderKey sk = new VkShaderKey(vertHash, fragHash, variant);

        if (rs == null) {
            return new VkPipelineKey(
                    sk,
                    passKey,
                    VK_CULL_MODE_NONE,
                    true,
                    true,
                    VK_COMPARE_OP_LESS_OR_EQUAL,
                    Blend.Off
            );
        }

        int vkCull;
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

        boolean depthTest = rs.isDepthTest();
        boolean depthWrite = rs.isDepthWrite();

        int compareOp;
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

        Blend blend;
        switch (rs.getBlendMode()) {
            case Off:
                blend = Blend.Off;
                break;
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

        return new VkPipelineKey(sk, passKey, vkCull, depthTest, depthWrite, compareOp, blend);
    }

}
