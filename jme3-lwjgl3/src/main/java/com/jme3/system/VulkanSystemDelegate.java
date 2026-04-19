package com.jme3.system;

import com.jme3.audio.AudioRenderer;
import com.jme3.renderer.vulkan.context.LwjglVulkanContext;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.ByteBuffer;

public final class VulkanSystemDelegate extends JmeSystemDelegate {

    @Override
    public URL getPlatformAssetConfigURL() {
        // 推荐：返回一个存在的 cfg，否则后续 Common/MatDefs 等会加载失败
        // return VulkanSystemDelegate.class.getResource("/com/jme3/asset/Desktop.cfg");
        return VulkanSystemDelegate.class.getResource("/com/jme3/asset/Vulkan.cfg");
    }

    @Override
    public JmeContext newContext(AppSettings settings, JmeContext.Type contextType) {
        LwjglVulkanContext ctx = new LwjglVulkanContext();
        ctx.setSettings(settings);
        return ctx;
    }

    @Override
    public AudioRenderer newAudioRenderer(AppSettings settings) {
        return new NullAudioRenderer(); // 你已经实现了这个
    }

    @Override
    public void initialize(AppSettings settings) {
        initialized = true;
    }

    @Override
    public void showSoftKeyboard(boolean show) {
        // no-op
    }

    @Override
    public void writeImageFile(OutputStream outStream, String format,
                               ByteBuffer imageData, int width, int height) throws IOException {
        throw new UnsupportedOperationException("writeImageFile not implemented");
    }
}
