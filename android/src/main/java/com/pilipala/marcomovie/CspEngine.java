package com.pilipala.marcomovie;

import android.app.Application;
import android.content.Context;
import dalvik.system.DexClassLoader;
import com.github.catvod.crawler.Spider;
import java.io.*;
import java.net.*;
import java.lang.reflect.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** Experimental ordinary CSP host. Never unwraps, patches, or bypasses plugin protection. */
public final class CspEngine implements SourceEngine {
    static final Map<String, DexClassLoader> LOADERS = new HashMap<>();
    static volatile String activeJarDeclaration = "";
    final Application app;
    final Cms.Site site;
    final Spider spider;
    final Object pluginLock;
    public final String pluginSha256;

    public CspEngine(Application application, Cms.Config config, Cms.Site selected)
            throws Exception {
        app = application;
        site = selected;
        if (!eligible(selected)) throw new IOException("此来源不是实验 CSP JAR 接口支持的 csp_ 站点");
        try {
            String primaryJar =
                    selected.jar != null && !selected.jar.trim().isEmpty()
                            ? selected.jar
                            : config.spider;
            File artifact;
            try {
                artifact = obtain(application, primaryJar);
                activeJarDeclaration = primaryJar;
            } catch (Exception firstError) {
                if (selected.jar != null
                        && !selected.jar.trim().isEmpty()
                        && config.spider != null
                        && !config.spider.trim().isEmpty()
                        && !selected.jar.equals(config.spider)) {
                    artifact = obtain(application, config.spider);
                    activeJarDeclaration = config.spider;
                } else {
                    throw firstError;
                }
            }
            pluginSha256 = digest(artifact, "SHA-256");
            DexClassLoader loader = loader(app, artifact, pluginSha256);
            pluginLock = new Object();
            try (InputStream resource = loader.getResourceAsStream("classes.dex")) {
                if (resource == null) throw new IOException("插件类加载器无法读取原始资源");
            }
            String className = "com.github.catvod.spider." + site.api.substring(4);
            Object candidate = loader.loadClass(className).getDeclaredConstructor().newInstance();
            if (!(candidate instanceof Spider)) throw new IOException("插件父接口与当前宿主不兼容");
            spider = (Spider) candidate;
            synchronized (pluginLock) {
                try {
                    spider.initApi(new com.github.catvod.crawler.SpiderApi());
                } catch (Throwable ignored) {
                }
                spider.init(app, site.ext == null ? "" : site.ext.toString());
            }
            diagnostic("loaded", className, null);
        } catch (Throwable error) {
            Throwable cause = unwrap(error);
            diagnostic("load_failed", site.api, cause);
            throw new EngineException(
                    "扩展引擎初始化失败：" + cause.getClass().getSimpleName() + "。此来源尚未验证兼容。", cause);
        }
    }

    static synchronized DexClassLoader loader(Application app, File artifact, String sha)
            throws Exception {
        DexClassLoader existing = LOADERS.get(sha);
        if (existing != null) return existing;
        File optimized = new File(app.getCodeCacheDir(), "csp-opt");
        if (!optimized.exists() && !optimized.mkdirs()) throw new IOException("无法创建插件私有目录");
        DexClassLoader loader =
                new DexClassLoader(
                        artifact.getAbsolutePath(),
                        optimized.getAbsolutePath(),
                        null,
                        app.getClassLoader());
        prepareHostStorage(app);
        List<Object> before = lifecycleCallbacks(app);
        loader.loadClass("com.github.catvod.spider.Init")
                .getMethod("init", Context.class)
                .invoke(null, app);
        syncPluginProxyPort(loader, ProxyAddress.port);
        replayForegroundActivity(app, before);
        LOADERS.put(sha, loader);
        return loader;
    }

    static void syncPluginProxyPort(ClassLoader loader, int port) {
        if (loader == null || port <= 0) return;
        try {
            Class<?> proxyClass = loader.loadClass("com.github.catvod.spider.Proxy");
            try {
                proxyClass.getMethod("set", int.class).invoke(null, port);
                return;
            } catch (Throwable ignored) {
            }
            for (Field f : proxyClass.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())
                        && (f.getType() == int.class || f.getType() == Integer.class)
                        && f.getName().toLowerCase(Locale.ROOT).contains("port")) {
                    f.setAccessible(true);
                    f.setInt(null, port);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Extensions written for TVBox-style hosts expect the usual private folders and a "tv"
     * database to exist. Missing ones made the extension's own setup stop half way.
     */
    static void prepareHostStorage(Application app) {
        try {
            File files = app.getFilesDir(), cache = app.getCacheDir();
            if (files != null && !files.exists()) files.mkdirs();
            if (cache != null && !cache.exists()) cache.mkdirs();
            File database = app.getDatabasePath("tv");
            File folder = database.getParentFile();
            if (folder != null && !folder.exists()) folder.mkdirs();
            if (!database.exists())
                app.openOrCreateDatabase("tv", Context.MODE_PRIVATE, null).close();
        } catch (Throwable error) {
            AppLog.w("CspEngine", "prepare host storage failed", error);
        }
    }

    @SuppressWarnings("unchecked")
    static List<Object> lifecycleCallbacks(Application app) {
        try {
            Field field = Application.class.getDeclaredField("mActivityLifecycleCallbacks");
            field.setAccessible(true);
            Object value = field.get(app);
            if (value instanceof List)
                synchronized (value) {
                    return new ArrayList<>((List<Object>) value);
                }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * The extension registers lifecycle callbacks when it is first loaded, which is after the
     * app screen is already open. Tell only those new callbacks about the visible screen, so
     * login dialogs have a window to attach to instead of crashing on a missing activity.
     */
    static void replayForegroundActivity(Application app, List<Object> before) {
        if (before == null) return;
        List<Object> after = lifecycleCallbacks(app);
        if (after == null) return;
        final List<Application.ActivityLifecycleCallbacks> added = new ArrayList<>();
        for (Object callback : after)
            if (!before.contains(callback)
                    && callback instanceof Application.ActivityLifecycleCallbacks) {
                Application.ActivityLifecycleCallbacks rawCb =
                        (Application.ActivityLifecycleCallbacks) callback;
                Application.ActivityLifecycleCallbacks safeCb = wrapLifecycleCallback(rawCb);
                try {
                    app.unregisterActivityLifecycleCallbacks(rawCb);
                    app.registerActivityLifecycleCallbacks(safeCb);
                } catch (Throwable ignored) {
                }
                added.add(safeCb);
            }
        if (added.isEmpty()) return;
        new android.os.Handler(android.os.Looper.getMainLooper())
                .post(
                        () -> {
                            MainActivity activity = MainActivity.activeActivity;
                            if (activity == null || activity.isFinishing()) return;
                            for (Application.ActivityLifecycleCallbacks callback : added)
                                try {
                                    callback.onActivityCreated(activity, null);
                                    callback.onActivityStarted(activity);
                                    if (activity.foreground) callback.onActivityResumed(activity);
                                } catch (Throwable error) {
                                    AppLog.w(
                                            "CspEngine",
                                            "extension lifecycle replay failed",
                                            error);
                                }
                        });
    }

    private static Application.ActivityLifecycleCallbacks wrapLifecycleCallback(
            final Application.ActivityLifecycleCallbacks delegate) {
        return new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(android.app.Activity a, android.os.Bundle b) {
                try {
                    delegate.onActivityCreated(a, b);
                } catch (Throwable e) {
                    AppLog.w("CspEngine", "plugin onActivityCreated failed", e);
                }
            }

            @Override
            public void onActivityStarted(android.app.Activity a) {
                try {
                    delegate.onActivityStarted(a);
                } catch (Throwable e) {
                    AppLog.w("CspEngine", "plugin onActivityStarted failed", e);
                }
            }

            @Override
            public void onActivityResumed(android.app.Activity a) {
                try {
                    delegate.onActivityResumed(a);
                } catch (Throwable e) {
                    AppLog.w("CspEngine", "plugin onActivityResumed failed", e);
                }
            }

            @Override
            public void onActivityPaused(android.app.Activity a) {
                try {
                    delegate.onActivityPaused(a);
                } catch (Throwable e) {
                    AppLog.w("CspEngine", "plugin onActivityPaused failed", e);
                }
            }

            @Override
            public void onActivityStopped(android.app.Activity a) {
                try {
                    delegate.onActivityStopped(a);
                } catch (Throwable e) {
                    AppLog.w("CspEngine", "plugin onActivityStopped failed", e);
                }
            }

            @Override
            public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle b) {
                try {
                    delegate.onActivitySaveInstanceState(a, b);
                } catch (Throwable e) {
                    AppLog.w("CspEngine", "plugin onActivitySaveInstanceState failed", e);
                }
            }

            @Override
            public void onActivityDestroyed(android.app.Activity a) {
                try {
                    delegate.onActivityDestroyed(a);
                } catch (Throwable e) {
                    AppLog.w("CspEngine", "plugin onActivityDestroyed failed", e);
                }
            }
        };
    }

    public static boolean eligible(Cms.Site s) {
        return s.type == 3
                && s.api.startsWith("csp_")
                && s.api.substring(4).matches("[A-Za-z0-9_$.]+");
    }

    public static Object[] proxy(Application app, Cms.Config config, Map<String, String> params)
            throws Exception {
        String decl =
                activeJarDeclaration != null && !activeJarDeclaration.isEmpty()
                        ? activeJarDeclaration
                        : config.spider;
        File artifact = obtain(app, decl);
        DexClassLoader loader = loader(app, artifact, digest(artifact, "SHA-256"));
        // playerContent may call its own HTTP proxy synchronously. Holding its
        // catalogue lock here would deadlock that reentrant network request.
        try {
            return (Object[])
                    loader.loadClass("com.github.catvod.spider.Proxy")
                            .getMethod("proxy", Map.class)
                            .invoke(null, params);
        } catch (InvocationTargetException e) {
            throw new IOException("来源代理执行失败", unwrap(e));
        }
    }

    public static final class EngineException extends Exception {
        EngineException(String message, Throwable t) {
            super(message, t);
        }
    }

    private interface Call {
        String run() throws Exception;
    }

    private String call(String operation, Call c) throws Exception {
        synchronized (pluginLock) {
            try {
                String result = c.run();
                if (result == null || result.trim().isEmpty()) throw new IOException("插件返回空结果");
                new JSONObject(result);
                diagnostic(operation + "_ok", site.api, null);
                return result;
            } catch (Throwable t) {
                Throwable error = unwrap(t);
                diagnostic(operation + "_failed", site.api, error);
                throw new EngineException(
                        "扩展来源请求失败：" + error.getClass().getSimpleName() + "。请检查来源或稍后重试。", error);
            }
        }
    }

    public String home() throws Exception {
        return call("home", () -> spider.homeContent(true));
    }

    public String homeVideos() throws Exception {
        return call("recommendations", () -> spider.homeVideoContent());
    }

    public String category(String id, int page) throws Exception {
        return category(id, page, Collections.emptyMap());
    }

    public String category(String id, int page, Map<String, String> filters) throws Exception {
        HashMap<String, String> values = new HashMap<>(filters);
        return call("category", () -> spider.categoryContent(id, "" + page, true, values));
    }

    public String detail(String id) throws Exception {
        return call("detail", () -> spider.detailContent(Collections.singletonList(id)));
    }

    public String search(String keyword, int page) throws Exception {
        return call("search", () -> spider.searchContent(keyword, false, "" + page));
    }

    public String player(String flag, String id) throws Exception {
        return call(
                "player",
                () -> {
                    Throwable lastError = null;
                    String raw = null;
                    for (int attempt = 0; attempt < 2; attempt++) {
                        try {
                            raw = spider.playerContent(flag, id, Collections.emptyList());
                            if (raw != null && !raw.trim().isEmpty()) {
                                break;
                            }
                        } catch (Throwable t) {
                            lastError = unwrap(t);
                            if (attempt == 0) {
                                try {
                                    Thread.sleep(150);
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                    break;
                                }
                            }
                        }
                    }
                    if (raw != null) {
                        String trimmed = raw.trim();
                        if (!trimmed.isEmpty()) {
                            if (trimmed.startsWith("{")) {
                                return trimmed;
                            }
                            if (Cms.isHttp(trimmed)
                                    || Cms.isMedia(trimmed)
                                    || trimmed.startsWith("magnet:?")) {
                                return new JSONObject()
                                        .put("url", trimmed)
                                        .put("parse", 0)
                                        .toString();
                            }
                        }
                    }
                    String fallbackId = id == null ? "" : id.trim();
                    if (!fallbackId.isEmpty()
                            && (Cms.isMedia(fallbackId) || fallbackId.startsWith("magnet:?"))) {
                        return new JSONObject().put("url", fallbackId).put("parse", 0).toString();
                    }
                    if (lastError != null) {
                        if (lastError instanceof Exception) throw (Exception) lastError;
                        throw new IOException(lastError);
                    }
                    return new JSONObject().put("url", "").put("parse", 0).toString();
                });
    }

    public String action(String value) throws Exception {
        synchronized (pluginLock) {
            try {
                return spider.action(value);
            } catch (Throwable error) {
                throw new EngineException(
                        "来源交互失败：" + unwrap(error).getClass().getSimpleName(), unwrap(error));
            }
        }
    }

    public boolean requiresExternalRuntime() {
        return true;
    }

    public void close() {
        synchronized (pluginLock) {
            try {
                spider.destroy();
            } catch (Throwable e) {
                AppLog.w("CspEngine", "destroy failed: " + site.api, e);
            }
        }
    }

    static Throwable unwrap(Throwable t) {
        while (t instanceof InvocationTargetException
                && ((InvocationTargetException) t).getCause() != null)
            t = ((InvocationTargetException) t).getCause();
        return t;
    }

    static String digest(File file, String algorithm) throws Exception {
        MessageDigest d = MessageDigest.getInstance(algorithm);
        try (InputStream in = new FileInputStream(file)) {
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) != -1) d.update(b, 0, n);
        }
        StringBuilder out = new StringBuilder();
        for (byte v : d.digest()) out.append(String.format(Locale.ROOT, "%02x", v & 255));
        return out.toString();
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, Object> ARTIFACT_LOCKS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, VerifiedArtifact>
            VERIFIED_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private static final class VerifiedArtifact {
        final String path;
        final long length;
        final long lastModified;
        final String sha256;
        final String md5;

        VerifiedArtifact(File file, String sha256, String md5) {
            this.path = file.getAbsolutePath();
            this.length = file.length();
            this.lastModified = file.lastModified();
            this.sha256 = sha256;
            this.md5 = md5;
        }

        boolean matches(File file, String expectedSha, String expectedMd5) {
            return file.exists()
                    && path.equals(file.getAbsolutePath())
                    && length == file.length()
                    && lastModified == file.lastModified()
                    && sha256.equals(expectedSha)
                    && (expectedMd5.isEmpty() || expectedMd5.equals(md5));
        }
    }

    static boolean verifyCachedArtifact(File cached, String sha, String expectedMd5)
            throws Exception {
        if (!cached.exists()) return false;
        VerifiedArtifact v = VERIFIED_CACHE.get(sha);
        if (v != null && v.matches(cached, sha, expectedMd5)) return true;
        String actualSha = digest(cached, "SHA-256");
        if (!sha.equals(actualSha)) return false;
        String actualMd5 = expectedMd5.isEmpty() ? "" : digest(cached, "MD5");
        if (!expectedMd5.isEmpty() && !expectedMd5.equals(actualMd5)) return false;
        VERIFIED_CACHE.put(sha, new VerifiedArtifact(cached, actualSha, actualMd5));
        return true;
    }

    static File obtain(Application app, String declaration) throws Exception {
        if (declaration == null || declaration.trim().isEmpty())
            throw new IOException("配置没有声明 spider 插件");
        String[] parts = declaration.split(";md5;", 2);
        String address = parts[0];
        if (address.startsWith("file://")) {
            File localFile = new File(URI.create(address));
            String allowedPrefix = app.getFilesDir().getCanonicalPath() + File.separator;
            if (!localFile.exists() || !localFile.getCanonicalPath().startsWith(allowedPrefix)) {
                throw new IOException("本地 spider 插件不存在或不在受保护目录内");
            }
            try (ZipFile zip = new ZipFile(localFile)) {
                if (zip.getEntry("classes.dex") == null)
                    throw new IOException("本地插件不含 classes.dex");
            }
            return localFile;
        }
        if (!Cms.isHttp(address)) throw new IOException("spider 需要 HTTP(S) 插件地址");
        String expected = parts.length == 2 ? parts[1].trim().toLowerCase(Locale.ROOT) : "";
        if (!expected.isEmpty() && !expected.matches("[0-9a-f]{32}"))
            throw new IOException("插件 MD5 声明无效");
        File folder = new File(app.getFilesDir(), "csp");
        if (!folder.exists() && !folder.mkdirs()) throw new IOException("插件目录不可用");
        String key = hex(declaration.getBytes("UTF-8"));
        Object lock = ARTIFACT_LOCKS.get(key);
        if (lock == null) {
            Object candidate = new Object();
            Object prev = ARTIFACT_LOCKS.putIfAbsent(key, candidate);
            lock = prev != null ? prev : candidate;
        }
        synchronized (lock) {
            File identity = new File(folder, "selection-" + key + ".txt");
            if (identity.exists()) {
                String sha;
                try (InputStream in = new FileInputStream(identity)) {
                    sha = Cms.read(in, 128).trim();
                }
                if (sha.matches("[0-9a-f]{64}")) {
                    File cached = new File(folder, sha + ".jar");
                    if (verifyCachedArtifact(cached, sha, expected)) return cached;
                }
            }
            File temp = File.createTempFile("incoming-", ".tmp", folder);
            try {
                URL next = new URL(Cms.normalizeHttp(address));
                boolean done = false;
                for (int redirect = 0; redirect < 5 && !done; redirect++) {
                    RequestScope.current().check();
                    HttpURLConnection c = (HttpURLConnection) next.openConnection();
                    c.setConnectTimeout(12000);
                    c.setReadTimeout(20000);
                    c.setInstanceFollowRedirects(false);
                    c.setRequestProperty("User-Agent", Cms.USER_AGENT);
                    RequestScope.attach(c);
                    try {
                        int code = c.getResponseCode();
                        if (code == 301
                                || code == 302
                                || code == 303
                                || code == 307
                                || code == 308) {
                            String loc = c.getHeaderField("Location");
                            if (loc == null) throw new IOException("插件重定向无地址");
                            next = new URL(next, loc);
                            if (!Cms.isHttp(next.toString())) throw new IOException("插件重定向协议不受支持");
                            continue;
                        }
                        if (code < 200 || code >= 300) throw new IOException("插件下载失败 HTTP " + code);
                        try (InputStream in = c.getInputStream();
                                OutputStream out = new FileOutputStream(temp)) {
                            byte[] b = new byte[8192];
                            int n, total = 0;
                            while ((n = in.read(b)) != -1) {
                                RequestScope.current().check();
                                total += n;
                                if (total > 20 * 1024 * 1024)
                                    throw new IOException("插件超过 20 MB 限制");
                                out.write(b, 0, n);
                            }
                        }
                        done = true;
                    } finally {
                        RequestScope.detach(c);
                        c.disconnect();
                    }
                }
                if (!done) throw new IOException("插件重定向次数过多");
                String actualMd5 = expected.isEmpty() ? "" : digest(temp, "MD5");
                if (!expected.isEmpty() && !expected.equals(actualMd5))
                    throw new IOException("插件 MD5 与配置不一致，已拒绝加载");
                try (ZipFile zip = new ZipFile(temp)) {
                    if (zip.getEntry("classes.dex") == null)
                        throw new IOException("插件不含 classes.dex");
                }
                String sha = digest(temp, "SHA-256");
                File artifact = new File(folder, sha + ".jar");
                if (artifact.exists()) {
                    if (!verifyCachedArtifact(artifact, sha, expected))
                        throw new IOException("缓存插件摘要不一致");
                } else if (!temp.renameTo(artifact)) throw new IOException("无法保存插件");
                artifact.setReadOnly();
                VERIFIED_CACHE.put(sha, new VerifiedArtifact(artifact, sha, actualMd5));
                try (OutputStream out = new FileOutputStream(identity)) {
                    out.write(sha.getBytes("UTF-8"));
                }
                return artifact;
            } finally {
                temp.delete();
            }
        }
    }

    static String hex(byte[] bytes) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder s = new StringBuilder();
        for (byte b : d) s.append(String.format(Locale.ROOT, "%02x", b & 255));
        return s.toString();
    }

    void diagnostic(String state, String className, Throwable error) {
        if (error != null) AppLog.w("CspEngine", state + ":" + className, error);
        try {
            JSONObject j = new JSONObject();
            j.put("state", state);
            j.put("class", className);
            j.put("error_type", error == null ? JSONObject.NULL : error.getClass().getName());
            j.put("elapsed_ms", android.os.SystemClock.elapsedRealtime());
            if (error != null) {
                JSONArray stack = new JSONArray();
                StackTraceElement[] elements = error.getStackTrace();
                for (int i = 0; i < Math.min(8, elements.length); i++)
                    stack.put(elements[i].getClassName() + "." + elements[i].getMethodName());
                j.put("frames", stack);
            }
            try (OutputStream out =
                    app.openFileOutput("csp-diagnostic.json", Context.MODE_PRIVATE)) {
                out.write(j.toString(2).getBytes("UTF-8"));
            }
        } catch (Exception ignored) {
        }
    }
}
