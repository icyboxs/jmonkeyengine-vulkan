/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package com.jme3.renderer.vulkan.util;

import com.jme3.renderer.vulkan.VkDebugFlags;
import static com.jme3.util.vulkan.IOUtils.ioResourceToByteBuffer;
import static org.lwjgl.BufferUtils.createByteBuffer;

import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.vulkan.EXTDebugReport.VK_ERROR_VALIDATION_FAILED_EXT;
import static org.lwjgl.vulkan.KHRDisplaySwapchain.VK_ERROR_INCOMPATIBLE_DISPLAY_KHR;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.NVRayTracing.*;
import static org.lwjgl.vulkan.VK10.*;

import java.io.IOException;
import java.nio.*;
import java.util.*;
import java.util.logging.Logger;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.*;
import org.lwjgl.util.shaderc.*;
import static org.lwjgl.util.shaderc.Shaderc.*;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.*;

public class VKUtil {

    private static final Logger LOGGER = Logger.getLogger(VKUtil.class.getName());

    public static final int VK_FLAGS_NONE = 0;

    private static int vulkanStageToShadercKind(int stage) {
        switch (stage) {
            case VK_SHADER_STAGE_VERTEX_BIT:
                return shaderc_vertex_shader;
            case VK_SHADER_STAGE_FRAGMENT_BIT:
                return shaderc_fragment_shader;
            case VK_SHADER_STAGE_RAYGEN_BIT_NV:
                return shaderc_raygen_shader;
            case VK_SHADER_STAGE_CLOSEST_HIT_BIT_NV:
                return shaderc_closesthit_shader;
            case VK_SHADER_STAGE_MISS_BIT_NV:
                return shaderc_miss_shader;
            case VK_SHADER_STAGE_ANY_HIT_BIT_NV:
                return shaderc_anyhit_shader;
            case VK_SHADER_STAGE_INTERSECTION_BIT_NV:
                return shaderc_intersection_shader;
            case VK_SHADER_STAGE_COMPUTE_BIT:
                return shaderc_compute_shader;
            default:
                throw new IllegalArgumentException("Stage: " + stage);
        }
    }

    public static ByteBuffer glslToSpirv(String classPath, int vulkanStage) throws IOException {
        ByteBuffer src = ioResourceToByteBuffer(classPath, 1024);

        long compiler = shaderc_compiler_initialize();
        long options = shaderc_compile_options_initialize();

        ShadercIncludeResolve resolver;
        ShadercIncludeResultRelease releaser;

        shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
        shaderc_compile_options_set_target_spirv(options, shaderc_spirv_version_1_4);
        shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);

        shaderc_compile_options_set_auto_map_locations(options, true);
        shaderc_compile_options_set_auto_bind_uniforms(options, true);

        shaderc_compile_options_set_include_callbacks(options,
                resolver = new ShadercIncludeResolve() {
            public long invoke(long user_data, long requested_source, int type, long requesting_source, long include_depth) {
                ShadercIncludeResult res = ShadercIncludeResult.calloc();
                try {
                    String src = classPath.substring(0, classPath.lastIndexOf('/')) + "/" + memUTF8(requested_source);
                    res.content(ioResourceToByteBuffer(src, 1024));
                    res.source_name(memUTF8(src));
                    return res.address();
                } catch (IOException e) {
                    throw new AssertionError("Failed to resolve include: " + src);
                }
            }
        },
                releaser = new ShadercIncludeResultRelease() {
            public void invoke(long user_data, long include_result) {
                ShadercIncludeResult result = ShadercIncludeResult.create(include_result);
                memFree(result.source_name());
                result.free();
            }
        },
                0L
        );

        long res;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            res = shaderc_compile_into_spv(
                    compiler,
                    src,
                    vulkanStageToShadercKind(vulkanStage),
                    stack.UTF8(classPath),
                    stack.UTF8("main"),
                    options
            );
            if (res == 0L) {
                throw new AssertionError("Internal error during compilation!");
            }
        }

        if (shaderc_result_get_compilation_status(res) != shaderc_compilation_status_success) {
            throw new AssertionError("Shader compilation failed: " + shaderc_result_get_error_message(res));
        }

        int size = (int) shaderc_result_get_length(res);
        ByteBuffer resultBytes = createByteBuffer(size);
        resultBytes.put(shaderc_result_get_bytes(res));
        resultBytes.flip();

        shaderc_result_release(res);
        shaderc_compile_options_release(options);
        shaderc_compiler_release(compiler);
        releaser.free();
        resolver.free();

        return resultBytes;
    }

    public static void _CHECK_(int ret, String msg) {
        if (ret != VK_SUCCESS) {
            throw new AssertionError(msg + ": " + translateVulkanResult(ret));
        }
    }

    public static void loadShader(VkPipelineShaderStageCreateInfo info,
            VkSpecializationInfo specInfo,
            MemoryStack stack,
            VkDevice device,
            String classPath,
            int stage) throws IOException {
        ByteBuffer shaderCode = glslToSpirv(classPath, stage);
        LongBuffer pShaderModule = stack.mallocLong(1);

        _CHECK_(
                vkCreateShaderModule(device,
                        VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(shaderCode).flags(0),
                        null,
                        pShaderModule),
                "Failed to create shader module"
        );

        info.stage(stage)
                .pSpecializationInfo(specInfo)
                .module(pShaderModule.get(0))
                .pName(stack.UTF8("main"));
    }

    public static String translateVulkanResult(int result) {
        switch (result) {
            case VK_SUCCESS:
                return "Command successfully completed.";
            case VK_NOT_READY:
                return "A fence or query has not yet completed.";
            case VK_TIMEOUT:
                return "A wait operation has not completed in the specified time.";
            case VK_EVENT_SET:
                return "An event is signaled.";
            case VK_EVENT_RESET:
                return "An event is unsignaled.";
            case VK_INCOMPLETE:
                return "A return array was too small for the result.";
            case VK_SUBOPTIMAL_KHR:
                return "A swapchain no longer matches the surface properties exactly, but can still be used to present to the surface successfully.";
            case VK_ERROR_OUT_OF_HOST_MEMORY:
                return "A host memory allocation has failed.";
            case VK_ERROR_OUT_OF_DEVICE_MEMORY:
                return "A device memory allocation has failed.";
            case VK_ERROR_INITIALIZATION_FAILED:
                return "Initialization of an object could not be completed for implementation-specific reasons.";
            case VK_ERROR_DEVICE_LOST:
                return "The logical or physical device has been lost.";
            case VK_ERROR_MEMORY_MAP_FAILED:
                return "Mapping of a memory object has failed.";
            case VK_ERROR_LAYER_NOT_PRESENT:
                return "A requested layer is not present or could not be loaded.";
            case VK_ERROR_EXTENSION_NOT_PRESENT:
                return "A requested extension is not supported.";
            case VK_ERROR_FEATURE_NOT_PRESENT:
                return "A requested feature is not supported.";
            case VK_ERROR_INCOMPATIBLE_DRIVER:
                return "The requested version of Vulkan is not supported by the driver or is otherwise incompatible for implementation-specific reasons.";
            case VK_ERROR_TOO_MANY_OBJECTS:
                return "Too many objects of the type have already been created.";
            case VK_ERROR_FORMAT_NOT_SUPPORTED:
                return "A requested format is not supported on this device.";
            case VK_ERROR_SURFACE_LOST_KHR:
                return "A surface is no longer available.";
            case VK_ERROR_NATIVE_WINDOW_IN_USE_KHR:
                return "The requested window is already connected to a VkSurfaceKHR, or to some other non-Vulkan API.";
            case VK_ERROR_OUT_OF_DATE_KHR:
                return "A surface has changed in such a way that it is no longer compatible with the swapchain.";
            case VK_ERROR_INCOMPATIBLE_DISPLAY_KHR:
                return "The display used by a swapchain is incompatible.";
            case VK_ERROR_VALIDATION_FAILED_EXT:
                return "A validation layer found an error.";
            default:
                return String.format("%s [%d]", "Unknown", Integer.valueOf(result));
        }
    }

    public static final PointerBuffer allocateLayerBuffer(String[] layers) {
        final Set<String> availableLayers = getAvailableLayers();

        PointerBuffer ppEnabledLayerNames = memAllocPointer(layers.length);
        LOGGER.info("Using layers:");
        for (int i = 0; i < layers.length; i++) {
            final String layer = layers[i];
            if (availableLayers.contains(layer)) {
                LOGGER.info("\t" + layer);
                ppEnabledLayerNames.put(memUTF8(layer));
            }
        }
        ppEnabledLayerNames.flip();
        return ppEnabledLayerNames;
    }

    private static final Set<String> getAvailableLayers() {
        final Set<String> res = new HashSet<>();
        final int[] ip = new int[1];

        vkEnumerateInstanceLayerProperties(ip, null);
        final int count = ip[0];

        try (final MemoryStack stack = MemoryStack.stackPush()) {
            if (count > 0) {
                final VkLayerProperties.Buffer instanceLayers = VkLayerProperties.malloc(count, stack);
                vkEnumerateInstanceLayerProperties(ip, instanceLayers);
                for (int i = 0; i < count; i++) {
                    final String layerName = instanceLayers.get(i).layerNameString();
                    res.add(layerName);
                }
            }
        }

        return res;
    }

    public static PointerBuffer pointersOfElements(MemoryStack stack, CustomBuffer<?> buffer) {
        int remaining = buffer.remaining();
        long addr = buffer.address();
        long sizeof = buffer.sizeof();
        PointerBuffer pointerBuffer = stack.mallocPointer(remaining);
        for (int i = 0; i < remaining; i++) {
            pointerBuffer.put(i, addr + sizeof * i);
        }
        return pointerBuffer;
    }

    public static void validateAlignment(VmaAllocationInfo pAllocationInfo, long alignment) {
        if ((pAllocationInfo.offset() % alignment) != 0) {
            throw new AssertionError("Illegal offset alignment");
        }
    }

    /**
     * 从内存字符串编译 GLSL -> SPIR-V（纯 raw 路径，无 variant 注入）。
     */
    public static ByteBuffer glslToSpirvFromString(String source, String virtualPath, int vulkanStage) {
        if (source == null) {
            throw new IllegalArgumentException("source is null");
        }
        if (virtualPath == null) {
            virtualPath = "<memory>";
        }

        String stageName = stageName(vulkanStage);

        // 编译前打印（可控）
        if (VkDebugFlags.SHADER_PRINT_SOURCE && !VkDebugFlags.SHADER_PRINT_ONLY_ON_ERROR) {
            System.err.println("==== [ShaderDump:BEFORE] " + virtualPath + " stage=" + stageName + " ====");
            System.err.println(withLineNumbers(source));
        }

        if (VkDebugFlags.SHADER_CHECK_PP_BALANCE) {
            checkPreprocessorBalanceOrThrow(source, virtualPath, stageName);
        }

        long compiler = shaderc_compiler_initialize();
        long options = shaderc_compile_options_initialize();

        shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
        shaderc_compile_options_set_target_spirv(options, shaderc_spirv_version_1_4);
        shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);

        long res;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            byte[] bytes = source.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ByteBuffer src = stack.malloc(bytes.length);
            src.put(bytes).flip();

            res = shaderc_compile_into_spv(
                    compiler,
                    src,
                    vulkanStageToShadercKind(vulkanStage),
                    stack.UTF8(virtualPath),
                    stack.UTF8("main"),
                    options
            );

            if (res == 0L) {
                shaderc_compile_options_release(options);
                shaderc_compiler_release(compiler);
                throw new AssertionError("Internal error during shader compilation!");
            }
        }

        if (shaderc_result_get_compilation_status(res) != shaderc_compilation_status_success) {
            String msg = shaderc_result_get_error_message(res);

            if (VkDebugFlags.SHADER_PRINT_SOURCE) {
                System.err.println("==== [ShaderDump:ERROR] " + virtualPath + " stage=" + stageName + " ====");
                System.err.println(withLineNumbers(source));
                System.err.println("==== shaderc error ====");
                System.err.println(msg);
            }

            shaderc_result_release(res);
            shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
            throw new AssertionError("Shader compilation failed: " + msg);
        }

        int size = (int) shaderc_result_get_length(res);
        ByteBuffer resultBytes = createByteBuffer(size);
        resultBytes.put(shaderc_result_get_bytes(res)).flip();

        shaderc_result_release(res);
        shaderc_compile_options_release(options);
        shaderc_compiler_release(compiler);
        return resultBytes;
    }

    private static String withLineNumbers(String s) {
        String[] lines = s.split("\n", -1);
        StringBuilder out = new StringBuilder(lines.length * 32);
        for (int i = 0; i < lines.length; i++) {
            out.append(String.format("%4d", i + 1)).append(": ").append(lines[i]).append('\n');
        }
        return out.toString();
    }

    private static String stageName(int stage) {
        switch (stage) {
            case VK_SHADER_STAGE_VERTEX_BIT:
                return "vert";
            case VK_SHADER_STAGE_FRAGMENT_BIT:
                return "frag";
            case VK_SHADER_STAGE_COMPUTE_BIT:
                return "comp";
            default:
                return "stage_" + stage;
        }
    }

    private static void checkPreprocessorBalanceOrThrow(String src, String virtualPath, String stageName) {
        String[] lines = src.split("\n", -1);
        java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();

        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.startsWith("//")) {
                continue;
            }

            if (t.startsWith("#if ") || t.equals("#if") || t.startsWith("#ifdef") || t.startsWith("#ifndef")) {
                stack.push(i + 1);
            } else if (t.startsWith("#elif") || t.startsWith("#else")) {
                if (stack.isEmpty()) {
                    throw new IllegalStateException("[PP-BALANCE] unexpected " + t + " at line " + (i + 1)
                            + " in " + virtualPath + " (" + stageName + ")");
                }
            } else if (t.startsWith("#endif")) {
                if (stack.isEmpty()) {
                    throw new IllegalStateException("[PP-BALANCE] unexpected #endif at line " + (i + 1)
                            + " in " + virtualPath + " (" + stageName + ")");
                }
                stack.pop();
            }
        }

        if (!stack.isEmpty()) {
            throw new IllegalStateException("[PP-BALANCE] missing #endif, opened at line " + stack.peek()
                    + " in " + virtualPath + " (" + stageName + ")");
        }
    }

}
