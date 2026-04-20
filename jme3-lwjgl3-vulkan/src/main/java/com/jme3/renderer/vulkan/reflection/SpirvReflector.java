package com.jme3.renderer.vulkan.reflection;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcReflectedResource;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;

/**
 * SpirvReflector
 *
 * 真实反射（兼容当前 LWJGL-spvc 绑定）： - uniform_buffers -> UNIFORM_BUFFER_DYNAMIC -
 * sampled_images / separate_images -> COMBINED_IMAGE_SAMPLER（Phase A 实用优先） -
 * push constants declared size（失败回退16） - UBO members（Phase C，失败不阻断） - 任意异常
 * fallback，不阻断渲染
 */
public final class SpirvReflector {

    private static final Logger LOGGER = Logger.getLogger(SpirvReflector.class.getName());

    public VkReflectionResult reflect(ByteBuffer vertSpv, ByteBuffer fragSpv, int vertHash, int fragHash) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("[SpirvReflector] called vSpv="
                    + (vertSpv != null ? vertSpv.remaining() : 0)
                    + " fSpv="
                    + (fragSpv != null ? fragSpv.remaining() : 0));
        }

        try {
            List<VkReflectionResult.DescriptorBinding> allBindings = new ArrayList<>();
            List<VkReflectionResult.UboMember> allUboMembers = new ArrayList<>();

            int pcSizeV = 0;
            int pcSizeF = 0;

            if (vertSpv != null && vertSpv.remaining() > 0) {
                StageResult r = reflectStage(vertSpv, VK_SHADER_STAGE_VERTEX_BIT);
                allBindings.addAll(r.bindings);
                allUboMembers.addAll(r.uboMembers);
                pcSizeV = r.pushConstantSize;
            }
            if (fragSpv != null && fragSpv.remaining() > 0) {
                StageResult r = reflectStage(fragSpv, VK_SHADER_STAGE_FRAGMENT_BIT);
                allBindings.addAll(r.bindings);
                allUboMembers.addAll(r.uboMembers);
                pcSizeF = r.pushConstantSize;
            }

            if (allBindings.isEmpty()) {
                return fallbackResult(vertHash, fragHash);
            }

            List<VkReflectionResult.DescriptorBinding> mergedBindings = mergeBindings(allBindings);
            List<VkReflectionResult.UboMember> mergedUboMembers = dedupUboMembers(allUboMembers);

            int pcSize = Math.max(pcSizeV, pcSizeF);
            if (pcSize <= 0) {
                pcSize = 16;
            }
            pcSize = align4(Math.max(16, pcSize));

            List<VkPushConstantRangeInfo> pcs = Collections.singletonList(
                    new VkPushConstantRangeInfo(
                            0,
                            pcSize,
                            VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT
                    )
            );

            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("[SpirvReflector] reflected bindings=" + mergedBindings.size()
                        + " uboMembers=" + mergedUboMembers.size()
                        + " pcSize=" + pcSize);
            }

            return new VkReflectionResult(
                    vertHash,
                    fragHash,
                    mergedBindings,
                    mergedUboMembers,
                    pcs
            );
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[SpirvReflector] real reflection failed, fallback.", t);
            return fallbackResult(vertHash, fragHash);
        }
    }

    private StageResult reflectStage(ByteBuffer spv, int stageFlag) {
        List<VkReflectionResult.DescriptorBinding> bindings = new ArrayList<>();
        List<VkReflectionResult.UboMember> uboMembers = new ArrayList<>();
        int pushConstantSize = 0;

        ByteBuffer src = spv.duplicate();
        src.position(0);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pContext = stack.mallocPointer(1);
            check(Spvc.spvc_context_create(pContext), "spvc_context_create");
            long context = pContext.get(0);

            try {
                PointerBuffer pIr = stack.mallocPointer(1);
                check(Spvc.spvc_context_parse_spirv(
                        context,
                        src.asIntBuffer(),
                        src.remaining() / 4,
                        pIr),
                        "spvc_context_parse_spirv");
                long ir = pIr.get(0);

                PointerBuffer pCompiler = stack.mallocPointer(1);
                check(Spvc.spvc_context_create_compiler(
                        context,
                        Spvc.SPVC_BACKEND_NONE,
                        ir,
                        Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP,
                        pCompiler),
                        "spvc_context_create_compiler");
                long compiler = pCompiler.get(0);

                PointerBuffer pResources = stack.mallocPointer(1);
                check(Spvc.spvc_compiler_create_shader_resources(compiler, pResources),
                        "spvc_compiler_create_shader_resources");
                long resources = pResources.get(0);

                // 1) UBO descriptors
                reflectResourceList(
                        compiler,
                        resources,
                        Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER,
                        "UNIFORM_BUFFER_DYNAMIC",
                        stageFlag,
                        bindings
                );

                // 2) sampled images
                reflectResourceList(
                        compiler,
                        resources,
                        Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE,
                        "COMBINED_IMAGE_SAMPLER",
                        stageFlag,
                        bindings
                );

                // 3) separate images（Phase A 工程化并入 combined）
                reflectResourceList(
                        compiler,
                        resources,
                        Spvc.SPVC_RESOURCE_TYPE_SEPARATE_IMAGE,
                        "COMBINED_IMAGE_SAMPLER",
                        stageFlag,
                        bindings
                );

                // 4) separate samplers 仅诊断
                if (LOGGER.isLoggable(Level.FINE)) {
                    try (MemoryStack s2 = MemoryStack.stackPush()) {
                        PointerBuffer pList = s2.mallocPointer(1);
                        PointerBuffer pCount = s2.mallocPointer(1);
                        int rc = Spvc.spvc_resources_get_resource_list_for_type(
                                resources,
                                Spvc.SPVC_RESOURCE_TYPE_SEPARATE_SAMPLERS,
                                pList,
                                pCount
                        );
                        if (rc == Spvc.SPVC_SUCCESS) {
                            int cnt = (int) pCount.get(0);
                            if (cnt > 0) {
                                LOGGER.fine("[SpirvReflector] separate_samplers count=" + cnt + " (ignored)");
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }

                // 5) push constant size
                pushConstantSize = reflectPushConstantSize(compiler, resources);

                // 6) UBO members（Phase C）
                reflectUboMembers(compiler, resources, uboMembers);

                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("[SpirvReflector] stage=" + stageFlag
                            + " rawBindings=" + bindings.size()
                            + " rawUboMembers=" + uboMembers.size()
                            + " pcSize=" + pushConstantSize);
                }
            } finally {
                Spvc.spvc_context_destroy(context);
            }
        }

        return new StageResult(bindings, uboMembers, pushConstantSize);
    }

    private int reflectPushConstantSize(long compiler, long resources) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pList = stack.mallocPointer(1);
            PointerBuffer pCount = stack.mallocPointer(1);

            check(Spvc.spvc_resources_get_resource_list_for_type(
                    resources,
                    Spvc.SPVC_RESOURCE_TYPE_PUSH_CONSTANT,
                    pList,
                    pCount),
                    "spvc_resources_get_resource_list_for_type(push_constant)");

            int count = (int) pCount.get(0);
            if (count <= 0) {
                return 0;
            }

            SpvcReflectedResource.Buffer list = SpvcReflectedResource.create(pList.get(0), count);

            int maxSize = 0;
            for (int i = 0; i < count; i++) {
                SpvcReflectedResource r = list.get(i);
                int baseTypeId = r.base_type_id();

                PointerBuffer pSize = stack.mallocPointer(1);
                check(Spvc.spvc_compiler_get_declared_struct_size(compiler, baseTypeId, pSize),
                        "spvc_compiler_get_declared_struct_size");

                long sz = pSize.get(0);
                if (sz > Integer.MAX_VALUE) {
                    sz = Integer.MAX_VALUE;
                }
                if ((int) sz > maxSize) {
                    maxSize = (int) sz;
                }
            }
            return maxSize;
        } catch (Throwable t) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE, "[SpirvReflector] reflect push constant size failed.", t);
            }
            return 0;
        }
    }

    private void reflectResourceList(long compiler,
            long resources,
            int resourceType,
            String mappedType,
            int stageFlag,
            List<VkReflectionResult.DescriptorBinding> out) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pList = stack.mallocPointer(1);
            PointerBuffer pCount = stack.mallocPointer(1);

            check(Spvc.spvc_resources_get_resource_list_for_type(
                    resources,
                    resourceType,
                    pList,
                    pCount),
                    "spvc_resources_get_resource_list_for_type");

            int count = (int) pCount.get(0);
            if (count <= 0) {
                if (LOGGER.isLoggable(Level.INFO)) {
                    LOGGER.info("[ReflectDiag] stage=" + stageName(stageFlag)
                            + " resourceType=" + resourceType
                            + " mappedType=" + mappedType
                            + " count=0");
                }
                return;
            }

            if (LOGGER.isLoggable(Level.INFO)) {
                LOGGER.info("[ReflectDiag] stage=" + stageName(stageFlag)
                        + " resourceType=" + resourceType
                        + " mappedType=" + mappedType
                        + " count=" + count);
            }

            SpvcReflectedResource.Buffer list = SpvcReflectedResource.create(pList.get(0), count);
            for (int i = 0; i < count; i++) {
                SpvcReflectedResource r = list.get(i);

                int id = r.id();
                int set = Spvc.spvc_compiler_get_decoration(compiler, id, Spv.SpvDecorationDescriptorSet);
                int binding = Spvc.spvc_compiler_get_decoration(compiler, id, Spv.SpvDecorationBinding);

                String name = r.nameString();
                if (name == null || name.isEmpty()) {
                    name = "set" + set + "_binding" + binding;
                }

                if (LOGGER.isLoggable(Level.INFO)) {
                    LOGGER.info("[ReflectDiag] stage=" + stageName(stageFlag)
                            + " set=" + set
                            + " binding=" + binding
                            + " type=" + mappedType
                            + " name=" + name);
                }

                out.add(new VkReflectionResult.DescriptorBinding(
                        set,
                        binding,
                        mappedType,
                        stageFlag,
                        name
                ));
            }
        }
    }

    private static String stageName(int stageFlag) {
        if (stageFlag == VK_SHADER_STAGE_VERTEX_BIT) {
            return "vert";
        }
        if (stageFlag == VK_SHADER_STAGE_FRAGMENT_BIT) {
            return "frag";
        }
        return "stage_" + stageFlag;
    }

    /**
     * UBO member 反射（Phase C） 注意：不同 LWJGL-spvc 版本 member API 命名可能有差异；这里采用“尽力反射 +
     * 失败降级”策略。
     */
    private void reflectUboMembers(long compiler, long resources, List<VkReflectionResult.UboMember> out) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pList = stack.mallocPointer(1);
            PointerBuffer pCount = stack.mallocPointer(1);

            check(Spvc.spvc_resources_get_resource_list_for_type(
                    resources,
                    Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER,
                    pList,
                    pCount),
                    "spvc_resources_get_resource_list_for_type(uboMembers)");

            int count = (int) pCount.get(0);
            if (count <= 0) {
                return;
            }

            SpvcReflectedResource.Buffer list = SpvcReflectedResource.create(pList.get(0), count);

            for (int rIdx = 0; rIdx < count; rIdx++) {
                SpvcReflectedResource r = list.get(rIdx);

                int baseTypeId = r.base_type_id();
                String blockName = r.nameString();
                if (blockName == null || blockName.isEmpty()) {
                    blockName = "UBO";
                }

                // 关键：先拿 type handle，再用 type_get_num_member_types 拿成员数
                long typeHandle = Spvc.spvc_compiler_get_type_handle(compiler, baseTypeId);
                if (typeHandle == 0L) {
                    continue;
                }

                int memberCount = Spvc.spvc_type_get_num_member_types(typeHandle);
                if (memberCount <= 0) {
                    continue;
                }

                for (int i = 0; i < memberCount; i++) {
                    String memberName;
                    try {
                        memberName = Spvc.spvc_compiler_get_member_name(compiler, baseTypeId, i);
                        if (memberName == null || memberName.isEmpty()) {
                            memberName = "member_" + i;
                        }
                    } catch (Throwable t) {
                        memberName = "member_" + i;
                    }

                    IntBuffer pOff = stack.mallocInt(1);
                    int rcOff = Spvc.spvc_compiler_type_struct_member_offset(
                            compiler, typeHandle, i, pOff
                    );
                    if (rcOff != Spvc.SPVC_SUCCESS) {
                        continue;
                    }
                    int offset = pOff.get(0);

                    int size = -1;
                    try {
                        PointerBuffer pSize = stack.mallocPointer(1);
                        int rcSize = Spvc.spvc_compiler_get_declared_struct_member_size(
                                compiler, typeHandle, i, pSize
                        );
                        if (rcSize == Spvc.SPVC_SUCCESS) {
                            long sz = pSize.get(0);
                            if (sz > Integer.MAX_VALUE) {
                                sz = Integer.MAX_VALUE;
                            }
                            size = (int) sz;
                        }
                    } catch (Throwable ignored) {
                        // size 可未知
                    }

                    out.add(new VkReflectionResult.UboMember(
                            blockName,
                            memberName,
                            offset,
                            size
                    ));
                }
            }
        } catch (Throwable t) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE, "[SpirvReflector] reflectUboMembers failed.", t);
            }
        }
    }

    private static List<VkReflectionResult.DescriptorBinding> mergeBindings(
            List<VkReflectionResult.DescriptorBinding> in) {

        final class Key {

            final int set;
            final int binding;
            final String type;

            Key(int set, int binding, String type) {
                this.set = set;
                this.binding = binding;
                this.type = type;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) {
                    return true;
                }
                if (!(o instanceof Key)) {
                    return false;
                }
                Key key = (Key) o;
                return set == key.set && binding == key.binding && Objects.equals(type, key.type);
            }

            @Override
            public int hashCode() {
                return Objects.hash(set, binding, type);
            }
        }

        Map<Key, VkReflectionResult.DescriptorBinding> m = new LinkedHashMap<>();
        for (VkReflectionResult.DescriptorBinding b : in) {
            Key k = new Key(b.set, b.binding, b.type);
            VkReflectionResult.DescriptorBinding old = m.get(k);
            if (old == null) {
                m.put(k, b);
            } else {
                m.put(k, new VkReflectionResult.DescriptorBinding(
                        old.set,
                        old.binding,
                        old.type,
                        old.stageFlags | b.stageFlags,
                        (old.name != null && !old.name.isEmpty()) ? old.name : b.name
                ));
            }
        }

        return new ArrayList<>(m.values());
    }

    private static List<VkReflectionResult.UboMember> dedupUboMembers(List<VkReflectionResult.UboMember> in) {
        if (in == null || in.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashMap<String, VkReflectionResult.UboMember> m = new LinkedHashMap<>();
        for (VkReflectionResult.UboMember u : in) {
            if (u == null) {
                continue;
            }
            String key = (u.blockName != null ? u.blockName : "")
                    + "|"
                    + (u.memberName != null ? u.memberName : "")
                    + "|"
                    + u.offset;
            m.putIfAbsent(key, u);
        }
        return new ArrayList<>(m.values());
    }

    private static int align4(int v) {
        return (v + 3) & ~3;
    }

    private static void check(int result, String api) {
        if (result != Spvc.SPVC_SUCCESS) {
            throw new IllegalStateException(api + " failed, code=" + result);
        }
    }

    private static final class StageResult {

        final List<VkReflectionResult.DescriptorBinding> bindings;
        final List<VkReflectionResult.UboMember> uboMembers;
        final int pushConstantSize;

        StageResult(List<VkReflectionResult.DescriptorBinding> bindings,
                List<VkReflectionResult.UboMember> uboMembers,
                int pushConstantSize) {
            this.bindings = bindings;
            this.uboMembers = uboMembers;
            this.pushConstantSize = pushConstantSize;
        }
    }

    private static VkReflectionResult fallbackResult(int vertHash, int fragHash) {
        LOGGER.warning("[SpirvReflector] fallbackResult used: returning EMPTY reflection result (no ABI synthetic bindings).");
        return new VkReflectionResult(
                vertHash,
                fragHash,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

}
