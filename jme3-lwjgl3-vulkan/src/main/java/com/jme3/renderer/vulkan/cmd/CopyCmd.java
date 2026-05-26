package com.jme3.renderer.vulkan.cmd;

import com.jme3.texture.FrameBuffer;

/**
 * 封装 FrameBuffer 拷贝指令，以便在延迟渲染队列中执行。
 * @author icyboxs
 */
public final class CopyCmd {

    public FrameBuffer src;
    public FrameBuffer dst;
    public boolean copyColor;
    public boolean copyDepth;

    // --- 对象池实现，杜绝零碎分配 ---
    private static final CopyCmd[] POOL = new CopyCmd[64];
    private static int poolPtr = -1;

    public static CopyCmd acquire() {
        synchronized (POOL) {
            if (poolPtr >= 0) {
                return POOL[poolPtr--];
            }
        }
        return new CopyCmd();
    }

    public void recycle() {
        src = null;
        dst = null;
        copyColor = false;
        copyDepth = false;

        synchronized (POOL) {
            if (poolPtr < POOL.length - 1) {
                POOL[++poolPtr] = this;
            }
        }
    }
}