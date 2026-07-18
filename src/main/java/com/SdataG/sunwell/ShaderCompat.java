package com.SdataG.sunwell;

/**
 * Detects whether an Iris / Oculus shader pack is currently active, via reflection so the mod keeps no
 * hard dependency on it (and so this class is harmless on a dedicated server, where it simply reports
 * {@code false}). The state is cached; {@link #refresh()} re-queries and is called about once per client
 * tick, so the hot light-engine path only does a cheap volatile read.
 */
public final class ShaderCompat {

    // Iris (Fabric/NeoForge) and Oculus (Forge) both expose this stable API; older Oculus used coderbot.
    private static final String[] API_CLASSES = {
            "net.irisshaders.iris.api.v0.IrisApi",
            "net.coderbot.iris.api.v0.IrisApi"
    };

    private static boolean initialized;
    private static Object apiInstance;
    private static java.lang.reflect.Method inUseMethod;
    private static volatile boolean active;

    private ShaderCompat() {
    }

    /** Cached shader-active state from the last {@link #refresh()}. Cheap; safe from any thread. */
    public static boolean shadersActive() {
        return active;
    }

    /** Re-query Iris/Oculus. Call ~once per client tick; never from the hot light-engine path. */
    public static void refresh() {
        active = query();
    }

    private static boolean query() {
        if (!initialized) {
            init();
        }
        if (inUseMethod == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(inUseMethod.invoke(apiInstance));
        } catch (Throwable t) {
            return false;
        }
    }

    private static void init() {
        initialized = true;
        for (String name : API_CLASSES) {
            try {
                Class<?> api = Class.forName(name);
                apiInstance = api.getMethod("getInstance").invoke(null);
                inUseMethod = api.getMethod("isShaderPackInUse");
                return;
            } catch (Throwable ignored) {
                // try the next candidate, or give up: no Iris/Oculus present
            }
        }
    }
}
