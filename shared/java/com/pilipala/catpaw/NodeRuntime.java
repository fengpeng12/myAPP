package com.pilipala.catpaw;

/** One embedded Node engine for the application; subscriptions run in worker isolates. */
public final class NodeRuntime {
    private static boolean started, exited;
    private static String rootPath;

    private static native int run(String script, String root, String ready);

    public static synchronized void start(
            final String script, final String root, final String ready) throws java.io.IOException {
        if (exited || (started && !root.equals(rootPath)))
            throw new java.io.IOException("请重新打开应用以启动 CatPaw");
        if (started) return;
        if (android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) > 4096)
            throw new java.io.IOException("当前 CatPaw 运行组件尚不支持此设备的 16 KB 内存页，请使用其他片源");
        try {
            System.loadLibrary("node");
            System.loadLibrary("marco_node");
        } catch (UnsatisfiedLinkError error) {
            throw new java.io.IOException("安装包缺少兼容的 CatPaw 本机运行组件");
        }
        new java.io.File(ready).delete();
        rootPath = root;
        started = true;
        new Thread(
                        null,
                        new Runnable() {
                            public void run() {
                                try {
                                    NodeRuntime.run(script, root, ready);
                                } finally {
                                    synchronized (NodeRuntime.class) {
                                        exited = true;
                                    }
                                }
                            }
                        },
                        "catpaw-node",
                        8 * 1024 * 1024)
                .start();
    }
}
