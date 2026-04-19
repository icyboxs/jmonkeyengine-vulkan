package com.jme3.renderer.vulkan.context;

import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;
import java.util.function.BiConsumer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

public final class GlfwWindow {

    private final long handle;
    private int fbWidth;
    private int fbHeight;

    private GlfwWindow(long handle, int fbW, int fbH) {
        this.handle = handle;
        this.fbWidth = fbW;
        this.fbHeight = fbH;
    }

    public static GlfwWindow create(int w, int h, String title, BiConsumer<Integer, Integer> onResize) {
        if (!glfwInit()) throw new RuntimeException("Failed to initialize GLFW");

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);

        long win = glfwCreateWindow(w, h, title, NULL, NULL);
        if (win == NULL) throw new RuntimeException("Failed to create GLFW window");

        // 关键修复：创建后立刻获取真实 framebuffer size（像素）
        int fbW, fbH;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pW = stack.mallocInt(1);
            IntBuffer pH = stack.mallocInt(1);
            glfwGetFramebufferSize(win, pW, pH);
            fbW = pW.get(0);
            fbH = pH.get(0);
        }

        GlfwWindow gw = new GlfwWindow(win, fbW, fbH);

        glfwSetFramebufferSizeCallback(win, new GLFWFramebufferSizeCallback() {
            @Override
            public void invoke(long window, int width, int height) {
                // width/height 是 framebuffer size（像素）
                gw.fbWidth = width;
                gw.fbHeight = height;
                if (onResize != null) onResize.accept(width, height);
            }
        });

        return gw;
    }

    public long handle() { return handle; }
    public int fbWidth() { return fbWidth; }
    public int fbHeight() { return fbHeight; }

    public void pollEvents() { glfwPollEvents(); }
    public boolean shouldClose() { return glfwWindowShouldClose(handle); }
    public void requestClose() { glfwSetWindowShouldClose(handle, true); }
    public void show() { glfwShowWindow(handle); }
    public void setTitle(String t) { glfwSetWindowTitle(handle, t); }

    public void destroy() {
        glfwDestroyWindow(handle);
        //glfwTerminate();
    }
}
