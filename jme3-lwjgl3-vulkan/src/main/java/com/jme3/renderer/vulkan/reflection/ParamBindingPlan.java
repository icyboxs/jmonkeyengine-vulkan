package com.jme3.renderer.vulkan.reflection;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ParamBindingPlan {

    public static final class BindingSlot {

        public final int set;
        public final int binding;
        public final String type;

        public BindingSlot(int set, int binding, String type) {
            this.set = set;
            this.binding = binding;
            this.type = type;
        }

        @Override
        public int hashCode() {
            return 31 * (31 * 17 + set) + binding;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof BindingSlot)) {
                return false;
            }
            BindingSlot b = (BindingSlot) o;
            return set == b.set && binding == b.binding;
        }

        @Override
        public String toString() {
            return "BindingSlot{set=" + set + ", binding=" + binding + ", type=" + type + '}';
        }
    }

    public final Map<String, BindingSlot> samplerByName = new HashMap<>();

    // O(1) 高速缓存（假设最大 4 个 Set）
    private final BindingSlot[] textureSlots = new BindingSlot[4];
    private final BindingSlot[] uboSlots = new BindingSlot[4];

    public void setTextureSlot(int set, BindingSlot slot) {
        if (set >= 0 && set < 4) {
            textureSlots[set] = slot;
        }
    }

    public void setUboSlot(int set, BindingSlot slot) {
        if (set >= 0 && set < 4) {
            uboSlots[set] = slot;
        }
    }

    /**
     * 极速 O(1) 访问
     */
    public BindingSlot getTextureSlot(int set) {
        return (set >= 0 && set < 4) ? textureSlots[set] : null;
    }

    /**
     * 极速 O(1) 访问
     */
    public BindingSlot getUboSlot(int set) {
        return (set >= 0 && set < 4) ? uboSlots[set] : null;
    }

    // 【新增】：消除热路径 GC 分配的缓存
    private static final ConcurrentHashMap<String, String> NORM_CACHE = new ConcurrentHashMap<>(256);

    public static String normalizeParamName(String n) {
        if (n == null || (n = n.trim()).isEmpty()) {
            return "";
        }
        //将处理过的字符串永久缓存，后续查表为零分配 (Zero-Allocation)
        return NORM_CACHE.computeIfAbsent(n, key -> {
            String s = key;
            if (s.length() > 2 && (s.charAt(0) == 'm' || s.charAt(0) == 'M') && s.charAt(1) == '_') {
                s = s.substring(2);
            }
            char[] arr = s.toCharArray();
            for (int i = 0; i < arr.length; i++) {
                if (arr[i] >= 'A' && arr[i] <= 'Z') {
                    arr[i] = (char) (arr[i] + ('a' - 'A'));
                }
            }
            return new String(arr);
        });
    }
    

    private static String toLowerAscii(String s) {
        char[] arr = s.toCharArray();
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] >= 'A' && arr[i] <= 'Z') {
                arr[i] = (char) (arr[i] + ('a' - 'A'));
            }
        }
        return new String(arr);
    }

    public BindingSlot getTextureSlot(String paramName) {
        String k = normalizeParamName(paramName);
        if (k.isEmpty()) {
            return null;
        }
        return samplerByName.get(k);
    }

}
