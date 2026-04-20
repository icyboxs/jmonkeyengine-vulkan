package com.jme3.renderer.vulkan.context;

import com.jme3.input.lwjgl.GlfwKeyInput;
import com.jme3.input.lwjgl.GlfwMouseInput;
import com.jme3.math.Vector2f;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.vulkan.LwjglVKRenderer;
import com.jme3.renderer.vulkan.VKRenderer;
import com.jme3.renderer.vulkan.VulkanRuntime;
import com.jme3.system.AppSettings;
import com.jme3.system.Displays;
import com.jme3.system.JmeContext;
import com.jme3.system.NanoTimer;
import com.jme3.system.SystemListener;
import com.jme3.system.Timer;
import com.jme3.system.lwjgl.LwjglWindow;
import com.jme3.system.lwjgl.WindowSizeListener;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LwjglVulkanContext：把 Vulkan 渲染循环嵌入到 jME3 的 JmeContext 体系里。
 * 渲染循环跑在独立线程。
 * 【修改】：直接继承 LwjglWindow，从而原生兼容 jME 的 GlfwKeyInput/GlfwMouseInput。
 */
public class LwjglVulkanContext extends LwjglWindow {

    private final AppSettings settings = new AppSettings(true);

    // created: “引擎已初始化完成/Context 可用”
    private final AtomicBoolean created = new AtomicBoolean(false);
    // renderable: “窗口已创建、GLFW handle 有效（输入可初始化）”
    private final AtomicBoolean renderable = new AtomicBoolean(false);

    private final AtomicBoolean destroyed = new AtomicBoolean(false);
    private final Object createdLock = new Object();
    private final Object destroyedLock = new Object();

    private SystemListener engine;
    private VulkanRuntime runtime;
    private LwjglVKRenderer renderer;
    private Timer timer;
    private Thread renderThread;

    private GlfwKeyInput keyInput;
    private GlfwMouseInput mouseInput;

    private volatile long windowHandle = 0L;

    private final CopyOnWriteArrayList<WindowSizeListener> winSizeListeners = new CopyOnWriteArrayList<>();
    private int lastWinW = -1;
    private int lastWinH = -1;

    // ==========================================
    // 构造函数：必须调用父类 LwjglWindow 的构造器
    // ==========================================
    public LwjglVulkanContext() {
        super(JmeContext.Type.Display);
    }

    @Override
    public void run() {
        destroyed.set(false);

        runtime = new VulkanRuntime(settings);
        renderer = new LwjglVKRenderer(runtime);
        timer = getTimer();

        try {
            // 1) 创建 GLFW 窗口 + Vulkan
            runtime.init();
            windowHandle = runtime.getWindowHandle();
            renderable.set(true);

            renderer.initialize();

            // ==========================================
            // 因为现在直接继承了 LwjglWindow，所以可以直接传入 this
            // ==========================================
            keyInput = new GlfwKeyInput(this);
            mouseInput = new GlfwMouseInput(this);

            // 再让引擎初始化（这一步里会创建 InputManager，并 setInputListener）
            if (engine != null) {
                engine.initialize();
            }

            // 现在 listener 已经 set 了，再初始化输入（安装 GLFW callbacks）
            keyInput.initialize();
            mouseInput.initialize();

            // 可选但强烈建议：重置一次 context 回调，确保鼠标初始事件/窗口绑定正确
            keyInput.resetContext();
            mouseInput.resetContext();

            // 最后再标记 created
            synchronized (createdLock) {
                created.set(true);
                createdLock.notifyAll();
            }

            // 6) 主循环
            while (!runtime.shouldClose()) {
                runtime.pollEvents();
                pumpWindowSizeListeners();

                timer.update();
                float tpf = timer.getTimePerFrame();

                int fbW = runtime.getFramebufferWidth();
                int fbH = runtime.getFramebufferHeight();
                if (fbW <= 0 || fbH <= 0) {
                    runtime.pollEvents();
                    try {
                        Thread.sleep(16);
                    } catch (InterruptedException ignored) {
                    }
                    continue;
                }

                if (engine != null) {
                    engine.update();
                }

                runtime.renderFrame(tpf, renderer, null);
            }

        } catch (Throwable t) {
            t.printStackTrace();
            if (engine != null) {
                engine.handleError("Vulkan render thread crashed", t);
            }
        } finally {
            // 先销毁输入（解除 GLFW callbacks），再销毁 runtime/window
            try {
                if (keyInput != null) {
                    //keyInput.destroy();
                }
            } catch (Throwable ignored) {
            } finally {
                keyInput = null;
            }

            try {
                if (mouseInput != null) {
                    //mouseInput.destroy();
                }
            } catch (Throwable ignored) {
            } finally {
                mouseInput = null;
            }

            try {
                if (engine != null) {
                    engine.destroy();
                }
            } catch (Throwable ignored) {
            }

            try {
                if (runtime != null) {
                    runtime.cleanup();
                }
            } catch (Throwable ignored) {
            }

            renderable.set(false);
            windowHandle = 0L;

            synchronized (destroyedLock) {
                destroyed.set(true);
                destroyedLock.notifyAll();
            }
        }
    }

    // -------- JmeContext 覆写 (架空父类 OpenGL 逻辑) --------
    @Override
    public void create(boolean waitFor) {
        if (created.get()) {
            return;
        }

        renderThread = new Thread(this, "VulkanRenderThread");
        renderThread.start();

        if (waitFor) {
            synchronized (createdLock) {
                while (!created.get()) {
                    try {
                        createdLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    @Override
    public void destroy(boolean waitFor) {
        if (runtime != null) {
            runtime.requestClose();
        }

        if (waitFor) {
            synchronized (destroyedLock) {
                while (!destroyed.get()) {
                    try {
                        destroyedLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    @Override
    public boolean isCreated() {
        return created.get();
    }

    @Override
    public boolean isRenderable() {
        return renderable.get();
    }

    @Override
    public Type getType() {
        return Type.Display;
    }

    @Override
    public AppSettings getSettings() {
        return settings;
    }

    @Override
    public void setSettings(AppSettings s) {
        settings.copyFrom(s);
    }

    @Override
    public void setSystemListener(SystemListener l) {
        engine = l;
    }

    @Override
    public SystemListener getSystemListener() {
        return engine;
    }

    @Override
    public Timer getTimer() {
        return new NanoTimer();
    }

    @Override
    public Renderer getRenderer() {
        return renderer;
    }

    @Override
    public com.jme3.opencl.Context getOpenCLContext() {
        return null;
    }

    @Override
    public com.jme3.input.MouseInput getMouseInput() {
        return mouseInput;
    }

    @Override
    public com.jme3.input.KeyInput getKeyInput() {
        return keyInput;
    }

    @Override
    public com.jme3.input.JoyInput getJoyInput() {
        return null;
    }

    @Override
    public com.jme3.input.TouchInput getTouchInput() {
        return null;
    }

    @Override
    public void setTitle(String t) {
        if (runtime != null) {
            runtime.setTitle(t);
        }
    }

    @Override
    public int getWindowXPosition() {
        return 0;
    }

    @Override
    public int getWindowYPosition() {
        return 0;
    }

    @Override
    public void setAutoFlushFrames(boolean enabled) {
        // no-op for now
    }

    @Override
    public void restart() {
        // no-op
    }

    @Override
    public int getFramebufferWidth() {
        if (runtime != null && runtime.isInitialized()) {
            return runtime.getFramebufferWidth();
        }
        return settings.getWidth();
    }

    @Override
    public int getFramebufferHeight() {
        if (runtime != null && runtime.isInitialized()) {
            return runtime.getFramebufferHeight();
        }
        return settings.getHeight();
    }

    // -------- GlfwWindowInterface 覆写 --------
    @Override
    public long getWindowHandle() {
        return windowHandle;
    }

    @Override
    public Vector2f getWindowContentScale(Vector2f store) {
        if (store == null) {
            store = new Vector2f();
        }
        if (windowHandle == 0L) {
            store.set(1f, 1f);
            return store;
        }

        float[] sx = new float[1];
        float[] sy = new float[1];
        org.lwjgl.glfw.GLFW.glfwGetWindowContentScale(windowHandle, sx, sy);
        store.set(sx[0], sy[0]);
        return store;
    }

    @Override
    public void registerWindowSizeListener(WindowSizeListener listener) {
        if (listener != null) {
            winSizeListeners.add(listener);
        }
    }

    @Override
    public void removeWindowSizeListener(WindowSizeListener listener) {
        if (listener != null) {
            winSizeListeners.remove(listener);
        }
    }

    // 【核心修复】：通知 JME 核心引擎窗口尺寸发生改变
    private void pumpWindowSizeListeners() {
        if (runtime == null || !runtime.isInitialized()) {
            return;
        }

        int currentW = runtime.getFramebufferWidth();
        int currentH = runtime.getFramebufferHeight();

        if (currentW != lastWinW || currentH != lastWinH) {
            lastWinW = currentW;
            lastWinH = currentH;

            // 同步更新 JME 的 AppSettings
            settings.setResolution(lastWinW, lastWinH);

            // 必须通知 JME 核心引擎进行 Reshape！
            if (engine != null) {
                engine.reshape(lastWinW, lastWinH);
            }

            for (WindowSizeListener l : winSizeListeners) {
                l.onWindowSizeChanged(lastWinW, lastWinH);
            }
        }
    }

    @Override
    public Displays getDisplays() {
        return null;
    }

    @Override
    public int getPrimaryDisplay() {
        return 0;
    }
}