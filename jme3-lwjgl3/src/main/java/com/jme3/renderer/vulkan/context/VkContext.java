package com.jme3.renderer.vulkan.context;

import java.nio.ByteBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.glfw.GLFWVulkan.glfwCreateWindowSurface;
import static org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions;
import static org.lwjgl.util.vma.Vma.vmaCreateAllocator;
import static org.lwjgl.util.vma.Vma.vmaDestroyAllocator;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaVulkanFunctions;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;

import org.lwjgl.vulkan.KHRDynamicRendering;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;
import org.lwjgl.vulkan.VkPhysicalDeviceDynamicRenderingFeaturesKHR;

public final class VkContext {

    private final boolean debug;
    private final boolean enableValidation;

    VkInstance instance;
    VkPhysicalDevice physicalDevice;
    VkDevice device;
    VkQueue queue;

    private long minUniformBufferOffsetAlignment = 256L;

    long surface;

    VkPhysicalDeviceMemoryProperties memProperties;

    int colorFormat;
    int colorSpace;
    int depthFormat;

    int queueFamilyIndex = 0;

    // Validation / Debug Utils
    private long debugMessenger = VK_NULL_HANDLE;
    private VkDebugUtilsMessengerCallbackEXT debugCallback;
    private boolean debugUtilsEnabled = false;

    // 在 VkContext 类中添加字段
    private long vmaAllocator = VK_NULL_HANDLE;

    // 添加获取方法
    public long vmaAllocator() {
        return vmaAllocator;
    }

    public VkContext(boolean debug) {
        this.debug = debug;
        this.enableValidation = debug;
    }

    public void init(GlfwWindow window) {
        try (MemoryStack stack = MemoryStack.stackPush()) {

            PointerBuffer requiredExtensions = glfwGetRequiredInstanceExtensions();
            if (requiredExtensions == null) {
                throw new RuntimeException("glfwGetRequiredInstanceExtensions returned null");
            }

            // ===== Instance: validation + debug utils =====
            boolean useValidationLayer = false;
            if (enableValidation) {
                if (!isValidationLayerAvailable()) {
                    System.err.println("DEBUG requested but layer VK_LAYER_KHRONOS_validation is unavailable. "
                            + "Install Vulkan SDK/runtime with validation layers. Continue without validation layer.");
                } else {
                    useValidationLayer = true;
                    System.out.println("[Vulkan] Validation layer enabled: VK_LAYER_KHRONOS_validation");
                }
            }

            boolean useDebugUtils = false;
            if (enableValidation) {
                if (!isInstanceExtensionSupported(VK_EXT_DEBUG_UTILS_EXTENSION_NAME)) {
                    System.err.println("DEBUG requested but instance extension " + VK_EXT_DEBUG_UTILS_EXTENSION_NAME
                            + " is unavailable. Debug messenger will not be used.");
                } else {
                    useDebugUtils = true;
                }
            }
            debugUtilsEnabled = useDebugUtils;

            int extraExtCount = useDebugUtils ? 1 : 0;
            PointerBuffer instExt = stack.mallocPointer(requiredExtensions.remaining() + extraExtCount);
            instExt.put(requiredExtensions);
            if (useDebugUtils) {
                instExt.put(stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME));
            }
            instExt.flip();

            PointerBuffer enabledLayers = null;

            if (useValidationLayer) {
                // 1. 先在栈上分配一个 UTF8 字符串，并获取它的地址
                ByteBuffer layerName = stack.UTF8("VK_LAYER_KHRONOS_validation");
                // 2. 在栈上分配一个包含该地址的 PointerBuffer
                enabledLayers = stack.mallocPointer(1);
                enabledLayers.put(layerName);
                enabledLayers.flip();
            }
            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                    .pApplicationName(stack.UTF8("JME_Vulkan"))
                    .pEngineName(stack.UTF8("jMonkeyEngine"))
                    .apiVersion(VK_API_VERSION_1_2);

            VkInstanceCreateInfo instCI = VkInstanceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                    .pApplicationInfo(appInfo)
                    .ppEnabledExtensionNames(instExt);

            if (enabledLayers != null) {
                instCI.ppEnabledLayerNames(enabledLayers);
            }

            PointerBuffer pInst = stack.mallocPointer(1);
            int err = vkCreateInstance(instCI, null, pInst);

            if (err != VK_SUCCESS) {
                throw new RuntimeException("vkCreateInstance failed: " + err);
            }
            instance = new VkInstance(pInst.get(0), instCI);

            if (useDebugUtils) {
                createDebugMessenger();
                if (debugMessenger != VK_NULL_HANDLE) {
                    System.out.println("[Vulkan] Debug utils messenger enabled.");
                } else {
                    System.err.println("[Vulkan] Debug utils messenger NOT created (see previous warning).");
                }
            }

            // ===== Surface =====
            LongBuffer pSurf = stack.mallocLong(1);
            int sErr = glfwCreateWindowSurface(instance, window.handle(), null, pSurf);
            if (sErr != VK_SUCCESS) {
                throw new RuntimeException("glfwCreateWindowSurface failed: " + sErr);
            }
            surface = pSurf.get(0);

            // ===== Physical device =====
            IntBuffer pDevCount = stack.ints(0);
            int e1 = vkEnumeratePhysicalDevices(instance, pDevCount, null);
            if (e1 != VK_SUCCESS) {
                throw new RuntimeException("vkEnumeratePhysicalDevices(count) failed: " + e1);
            }
            if (pDevCount.get(0) == 0) {
                throw new RuntimeException("No Vulkan physical devices found");
            }

            PointerBuffer pDevs = stack.mallocPointer(pDevCount.get(0));
            int e2 = vkEnumeratePhysicalDevices(instance, pDevCount, pDevs);
            if (e2 != VK_SUCCESS) {
                throw new RuntimeException("vkEnumeratePhysicalDevices(list) failed: " + e2);
            }

            physicalDevice = new VkPhysicalDevice(pDevs.get(0), instance);

            // 设备扩展硬检查（关键）
            requireDeviceExtension(physicalDevice, VK_KHR_SWAPCHAIN_EXTENSION_NAME);
            requireDeviceExtension(physicalDevice, KHRDynamicRendering.VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME);

            memProperties = VkPhysicalDeviceMemoryProperties.calloc();
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, memProperties);

            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc();
            vkGetPhysicalDeviceProperties(physicalDevice, props);
            minUniformBufferOffsetAlignment = props.limits().minUniformBufferOffsetAlignment();
            props.free();

            queueFamilyIndex = findQueueFamilyIndex(physicalDevice, surface);
            System.out.println("[Vulkan] selected queueFamilyIndex=" + queueFamilyIndex);

            // ===== Logical device =====
            VkDeviceQueueCreateInfo.Buffer qCI = VkDeviceQueueCreateInfo.calloc(1, stack);
            qCI.get(0)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                    .queueFamilyIndex(queueFamilyIndex)
                    .pQueuePriorities(stack.floats(1.0f));

            PointerBuffer devExt = stack.pointers(
                    stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME),
                    stack.UTF8(KHRDynamicRendering.VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME)
            );

            VkPhysicalDeviceDynamicRenderingFeaturesKHR dyn
                    = VkPhysicalDeviceDynamicRenderingFeaturesKHR.calloc(stack)
                            .sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_FEATURES_KHR)
                            .dynamicRendering(true);

            VkDeviceCreateInfo dCI = VkDeviceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                    .pNext(dyn.address())
                    .pQueueCreateInfos(qCI)
                    .ppEnabledExtensionNames(devExt);

            PointerBuffer pDev = stack.mallocPointer(1);
            int dErr = vkCreateDevice(physicalDevice, dCI, null, pDev);
            if (dErr != VK_SUCCESS) {
                throw new RuntimeException("vkCreateDevice failed: " + dErr);
            }
            device = new VkDevice(pDev.get(0), physicalDevice, dCI);

            PointerBuffer pQ = stack.mallocPointer(1);
            vkGetDeviceQueue(device, queueFamilyIndex, 0, pQ);
            queue = new VkQueue(pQ.get(0), device);

            // Dynamic Rendering 函数指针检查（兼容旧版 LWJGL）
            long fpBeginRendering = vkGetDeviceProcAddr(device, "vkCmdBeginRenderingKHR");
            long fpEndRendering = vkGetDeviceProcAddr(device, "vkCmdEndRenderingKHR");

            // 某些实现可能暴露 core 1.3 名称，再补查一次
            if (fpBeginRendering == 0L) {
                fpBeginRendering = vkGetDeviceProcAddr(device, "vkCmdBeginRendering");
            }
            if (fpEndRendering == 0L) {
                fpEndRendering = vkGetDeviceProcAddr(device, "vkCmdEndRendering");
            }

            if (fpBeginRendering == 0L || fpEndRendering == 0L) {
                throw new IllegalStateException(
                        "Dynamic Rendering function pointers not loaded: begin=" + fpBeginRendering
                        + ", end=" + fpEndRendering
                        + " (checked KHR and core names)"
                );
            }

            System.out.println("[Vulkan] DynamicRendering fp OK: begin="
                    + fpBeginRendering + ", end=" + fpEndRendering);

            // ===== Surface format =====
            IntBuffer pFCount = stack.ints(0);
            int sf0 = vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, pFCount, null);
            if (sf0 != VK_SUCCESS) {
                throw new RuntimeException("vkGetPhysicalDeviceSurfaceFormatsKHR(count) failed: " + sf0);
            }

            int formatCount = pFCount.get(0);
            if (formatCount == 0) {
                throw new RuntimeException("No surface formats available");
            }

            VkSurfaceFormatKHR.Buffer surfFormats = VkSurfaceFormatKHR.calloc(formatCount, stack);
            int sf1 = vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, pFCount, surfFormats);
            if (sf1 != VK_SUCCESS) {
                throw new RuntimeException("vkGetPhysicalDeviceSurfaceFormatsKHR(list) failed: " + sf1);
            }

            if (formatCount == 1 && surfFormats.get(0).format() == VK_FORMAT_UNDEFINED) {
                colorFormat = VK_FORMAT_B8G8R8A8_UNORM;
            } else {
                colorFormat = surfFormats.get(0).format();
            }
            colorSpace = surfFormats.get(0).colorSpace();

            depthFormat = VK_FORMAT_D32_SFLOAT;
            // ==== [新增] 初始化 VMA Allocator ====
            VmaVulkanFunctions vulkanFunctions = VmaVulkanFunctions.calloc(stack)
                    .set(instance, device);

            VmaAllocatorCreateInfo allocatorInfo = VmaAllocatorCreateInfo.calloc(stack)
                    .physicalDevice(physicalDevice)
                    .device(device)
                    .instance(instance)
                    .pVulkanFunctions(vulkanFunctions)
                    .vulkanApiVersion(VK_API_VERSION_1_2);

            PointerBuffer pAllocator = stack.mallocPointer(1);
            int vmaErr = vmaCreateAllocator(allocatorInfo, pAllocator);
            if (vmaErr != VK_SUCCESS) {
                throw new RuntimeException("Failed to create VMA allocator: " + vmaErr);
            }
            vmaAllocator = pAllocator.get(0);
            System.out.println("[Vulkan] VMA Allocator initialized successfully.");
        }
    }

    /**
     * 检查物理设备是否支持指定 device extension。
     */
    private static void requireDeviceExtension(VkPhysicalDevice phys, String extensionName) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pCount = stack.ints(0);

            int err = vkEnumerateDeviceExtensionProperties(phys, (ByteBuffer) null, pCount, null);
            if (err != VK_SUCCESS) {
                throw new RuntimeException("vkEnumerateDeviceExtensionProperties(count) failed: " + err);
            }

            int count = pCount.get(0);
            if (count <= 0) {
                throw new RuntimeException("No device extensions reported, required: " + extensionName);
            }

            // 防御：异常值保护，避免把 MemoryStack 撑爆
            if (count > 4096) {
                throw new RuntimeException("Unreasonable device extension count: " + count);
            }

            VkExtensionProperties.Buffer exts = VkExtensionProperties.malloc(count);
            try {
                pCount.put(0, count);
                err = vkEnumerateDeviceExtensionProperties(phys, (ByteBuffer) null, pCount, exts);
                if (err != VK_SUCCESS) {
                    throw new RuntimeException("vkEnumerateDeviceExtensionProperties(list) failed: " + err);
                }

                int actual = pCount.get(0);
                if (actual < 0 || actual > count) {
                    throw new RuntimeException("Invalid extension count returned: " + actual + ", requested buffer=" + count);
                }

                for (int i = 0; i < actual; i++) {
                    if (extensionName.equals(exts.get(i).extensionNameString())) {
                        return;
                    }
                }
            } finally {
                exts.free();
            }

            throw new RuntimeException("Required device extension not supported: " + extensionName);
        }
    }

    public void waitIdle() {
        if (device != null) {
            vkDeviceWaitIdle(device);
        }
    }

    public void destroy() {
        // [新增] 销毁 VMA Allocator
        if (vmaAllocator != VK_NULL_HANDLE) {
            vmaDestroyAllocator(vmaAllocator);
            vmaAllocator = VK_NULL_HANDLE;
        }
        if (device != null) {
            vkDestroyDevice(device, null);
            device = null;
        }

        if (debugMessenger != VK_NULL_HANDLE && instance != null) {
            vkDestroyDebugUtilsMessengerEXT(instance, debugMessenger, null);
            debugMessenger = VK_NULL_HANDLE;
        }

        if (debugCallback != null) {
            debugCallback.free();
            debugCallback = null;
        }

        if (surface != 0 && instance != null) {
            vkDestroySurfaceKHR(instance, surface, null);
            surface = 0;
        }

        if (instance != null) {
            vkDestroyInstance(instance, null);
            instance = null;
        }

        if (memProperties != null) {
            memProperties.free();
            memProperties = null;
        }
    }

    public VkDevice device() {
        return device;
    }

    public VkPhysicalDevice physicalDevice() {
        return physicalDevice;
    }

    public VkInstance instance() {
        return instance;
    }

    public VkQueue queue() {
        return queue;
    }

    public long surface() {
        return surface;
    }

    public int queueFamilyIndex() {
        return queueFamilyIndex;
    }

    public int colorFormat() {
        return colorFormat;
    }

    public int colorSpace() {
        return colorSpace;
    }

    public int depthFormat() {
        return depthFormat;
    }

    public VkPhysicalDeviceMemoryProperties memProperties() {
        return memProperties;
    }

    public long minUniformBufferOffsetAlignment() {
        return minUniformBufferOffsetAlignment;
    }

    private int findQueueFamilyIndex(VkPhysicalDevice phys, long surface) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pCount = stack.ints(0);
            vkGetPhysicalDeviceQueueFamilyProperties(phys, pCount, null);
            int count = pCount.get(0);
            if (count <= 0) {
                throw new RuntimeException("No queue families found");
            }

            VkQueueFamilyProperties.Buffer qProps = VkQueueFamilyProperties.calloc(count, stack);
            vkGetPhysicalDeviceQueueFamilyProperties(phys, pCount, qProps);

            Integer fallbackGraphicsOnly = null;

            for (int i = 0; i < count; i++) {
                boolean graphics = (qProps.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0;

                IntBuffer pPresent = stack.ints(VK_FALSE);
                int err = vkGetPhysicalDeviceSurfaceSupportKHR(phys, i, surface, pPresent);
                if (err != VK_SUCCESS) {
                    throw new RuntimeException("vkGetPhysicalDeviceSurfaceSupportKHR failed: " + err + ", family=" + i);
                }
                boolean present = pPresent.get(0) == VK_TRUE;

                if (graphics && present) {
                    return i;
                }
                if (graphics && fallbackGraphicsOnly == null) {
                    fallbackGraphicsOnly = i;
                }
            }

            if (fallbackGraphicsOnly != null) {
                throw new RuntimeException("Found graphics queue family=" + fallbackGraphicsOnly
                        + " but no unified graphics+present family. Current backend requires unified family.");
            }

            throw new RuntimeException("No suitable graphics queue family found.");
        }
    }

    private boolean isValidationLayerAvailable() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pCount = stack.ints(0);
            int err = vkEnumerateInstanceLayerProperties(pCount, null);
            if (err != VK_SUCCESS) {
                throw new RuntimeException("vkEnumerateInstanceLayerProperties failed: " + err);
            }

            VkLayerProperties.Buffer props = VkLayerProperties.calloc(pCount.get(0), stack);
            err = vkEnumerateInstanceLayerProperties(pCount, props);
            if (err != VK_SUCCESS) {
                throw new RuntimeException("vkEnumerateInstanceLayerProperties(2) failed: " + err);
            }

            for (int i = 0; i < props.capacity(); i++) {
                if ("VK_LAYER_KHRONOS_validation".equals(props.get(i).layerNameString())) {
                    return true;
                }
            }
            return false;
        }
    }

    private boolean isInstanceExtensionSupported(String extensionName) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pCount = stack.ints(0);
            int err = vkEnumerateInstanceExtensionProperties((String) null, pCount, null);
            if (err != VK_SUCCESS) {
                throw new RuntimeException("vkEnumerateInstanceExtensionProperties failed: " + err);
            }

            VkExtensionProperties.Buffer props = VkExtensionProperties.calloc(pCount.get(0), stack);
            err = vkEnumerateInstanceExtensionProperties((String) null, pCount, props);
            if (err != VK_SUCCESS) {
                throw new RuntimeException("vkEnumerateInstanceExtensionProperties(2) failed: " + err);
            }

            for (int i = 0; i < props.capacity(); i++) {
                if (extensionName.equals(props.get(i).extensionNameString())) {
                    return true;
                }
            }
            return false;
        }
    }

    private void createDebugMessenger() {
        if (!debugUtilsEnabled || instance == null) {
            return;
        }

        long fpCreate = vkGetInstanceProcAddr(instance, "vkCreateDebugUtilsMessengerEXT");
        if (fpCreate == 0L) {
            System.err.println("[Vulkan] vkCreateDebugUtilsMessengerEXT proc not found. Skip debug messenger.");
            return;
        }

        if (debugCallback == null) {
            debugCallback = VkDebugUtilsMessengerCallbackEXT.create(
                    (messageSeverity, messageTypes, pCallbackData, pUserData) -> {
                        VkDebugUtilsMessengerCallbackDataEXT data
                        = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);
                        String msg = data.pMessageString();

                        if ((messageSeverity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) {
                            System.err.println("[VK-VALIDATION][ERROR] " + msg);
                        } else if ((messageSeverity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) != 0) {
                            System.err.println("[VK-VALIDATION][WARN ] " + msg);
                        } else {
                            System.out.println("[VK-VALIDATION][INFO ] " + msg);
                        }
                        return VK_FALSE;
                    }
            );
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDebugUtilsMessengerCreateInfoEXT ci = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT)
                    .pNext(0L)
                    .flags(0)
                    .messageSeverity(
                            VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT
                            | VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT
                    )
                    .messageType(
                            VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                    )
                    .pfnUserCallback(debugCallback)
                    .pUserData(0L);

            LongBuffer pMessenger = stack.mallocLong(1);
            int err = vkCreateDebugUtilsMessengerEXT(instance, ci, null, pMessenger);
            if (err != VK_SUCCESS) {
                throw new RuntimeException("vkCreateDebugUtilsMessengerEXT failed: " + err);
            }
            debugMessenger = pMessenger.get(0);
        }
    }
}
