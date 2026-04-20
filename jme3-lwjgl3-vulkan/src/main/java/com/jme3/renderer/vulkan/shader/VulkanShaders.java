package com.jme3.renderer.vulkan.shader;

import com.jme3.renderer.vulkan.context.VkContext;
import com.jme3.renderer.vulkan.util.VKUtil;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.jme3.renderer.vulkan.util.VKUtil.glslToSpirv;
import static org.lwjgl.vulkan.VK10.*;

/**
 * VulkanShaders：管理 VkShaderModule + ShaderArtifact（含 SPIR-V）。
 * @author icyboxs
 */
public final class VulkanShaders {

    private final VkContext vk;

    /** 所有创建过的模块句柄，destroy() 统一销毁 */
    private final List<Long> modules = new ArrayList<>();

    /** 缓存 key=(stage + glslHash) -> artifact */
    private final Map<Long, ShaderArtifact> shaderCache = new HashMap<>();

    public VulkanShaders(VkContext vk) {
        this.vk = vk;
    }

    /**
     * 从 classpath 加载 GLSL 并创建模块（不走 artifact 缓存）。
     */
    public long createModuleFromGlsl(String classpath, int stage) throws IOException {
        ByteBuffer spirv = glslToSpirv(classpath, stage);
        long mod = createModule(spirv);
        modules.add(mod);
        return mod;
    }

    /**
     * 兼容旧接口：返回 module。
     */
    public long getOrCreateModuleFromRawGlsl(String glsl, int stage, String debugName) {
        return getOrCreateArtifactFromRawGlsl(glsl, stage, debugName).module;
    }

    /**
     * 新接口：返回完整 artifact（module + spirv + hash + stage）。
     */
    public ShaderArtifact getOrCreateArtifactFromRawGlsl(String glsl, int stage, String debugName) {
        if (glsl == null) {
            throw new IllegalArgumentException("glsl is null");
        }

        long key = (((long) stage) << 32) ^ (glsl.hashCode() & 0xffffffffL);
        ShaderArtifact cached = shaderCache.get(key);
        if (cached != null) {
            return cached;
        }

        ByteBuffer spirvCompiled = VKUtil.glslToSpirvFromString(glsl, debugName, stage);
        ByteBuffer spirvCopy = cloneDirect(spirvCompiled).asReadOnlyBuffer();

        long mod = createModule(spirvCompiled);
        modules.add(mod);

        ShaderArtifact artifact = new ShaderArtifact(mod, spirvCopy, glsl.hashCode(), stage);
        shaderCache.put(key, artifact);
        return artifact;
    }

    public void destroy() {
        for (long m : modules) {
            vkDestroyShaderModule(vk.device(), m, null);
        }
        modules.clear();
        shaderCache.clear();
    }

    private long createModule(ByteBuffer code) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkShaderModuleCreateInfo ci = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default()
                    .pCode(code);

            LongBuffer p = stack.mallocLong(1);
            int err = vkCreateShaderModule(vk.device(), ci, null, p);
            if (err != VK_SUCCESS) {
                throw new RuntimeException("vkCreateShaderModule failed: " + err);
            }
            return p.get(0);
        }
    }

    private static ByteBuffer cloneDirect(ByteBuffer src) {
        ByteBuffer s = src.duplicate();
        s.position(0);
        ByteBuffer out = ByteBuffer.allocateDirect(s.remaining());
        out.put(s);
        out.flip();
        return out;
    }
}
