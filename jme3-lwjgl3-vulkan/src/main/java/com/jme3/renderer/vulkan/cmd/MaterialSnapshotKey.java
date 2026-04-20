package com.jme3.renderer.vulkan.cmd;

import com.jme3.texture.Texture;

import java.util.Objects;

/**
 * CPU 侧材质快照 key。
 *
 * 作用：
 * - 表达一个 draw 在前端提交阶段看到的“材质纹理语义快照”
 * - 当前最小版只包含 tex0/light
 * - 未来可扩展为更多 material 语义项
 *
 * 注意：
 * - 这里按对象身份（identity）区分纹理，而不是 deep equals
 * - 这是为了贴合运行时资源缓存模型（Texture -> VkTexture / Sampler）
 */
public final class MaterialSnapshotKey {

    public final Texture tex0;
    public final Texture light;

    public MaterialSnapshotKey(Texture tex0, Texture light) {
        this.tex0 = tex0;
        this.light = light;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MaterialSnapshotKey)) return false;
        MaterialSnapshotKey that = (MaterialSnapshotKey) o;
        return tex0 == that.tex0 && light == that.light;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                System.identityHashCode(tex0),
                System.identityHashCode(light)
        );
    }

    @Override
    public String toString() {
        return "MaterialSnapshotKey{tex0=" + texDebug(tex0)
                + ", light=" + texDebug(light)
                + '}';
    }

    private static String texDebug(Texture t) {
        if (t == null) {
            return "null";
        }
        String name = t.getName();
        // 关键：即使 name 为 null，也能看出对象是存在的
        return "@" + System.identityHashCode(t) + (name != null ? ("/" + name) : "/<noname>");
    }
}
