package com.pilipala.catpaw;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.Locale;
import org.json.*;

/** On-device CatPaw transport. Persist logical URLs, never the local capability or port. */
public final class CatPawHost {
    private static String endpoint;
    private final Context context;
    private final File root;

    public CatPawHost(Context context) {
        this.context = context.getApplicationContext();
        root = new File(this.context.getFilesDir(), "catpaw");
    }

    public static boolean isBundle(String code) {
        return code.contains("catServerFactory") && code.contains("catDartServerPort");
    }

    private static byte[] bytes(InputStream input, int maximum) throws IOException {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > maximum) throw new IOException("CatPaw 响应过大");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static void write(File file, byte[] content) throws IOException {
        FileOutputStream stream = new FileOutputStream(file);
        try {
            stream.write(content);
            stream.getFD().sync();
        } finally {
            stream.close();
        }
    }

    private String start() throws Exception {
        synchronized (CatPawHost.class) {
            if (endpoint != null) return endpoint;
            if (!root.isDirectory() && !root.mkdirs()) throw new IOException("无法创建 CatPaw 本机目录");
            for (String name : new String[] {"host.cjs", "fetch.cjs"}) {
                write(
                        new File(root, name),
                        bytes(context.getAssets().open("catpaw/" + name), 1024 * 1024));
            }
            File ready = new File(root, "ready.json");
            NodeRuntime.start(
                    new File(root, "host.cjs").getAbsolutePath(),
                    root.getAbsolutePath(),
                    ready.getAbsolutePath());
            long deadline = System.nanoTime() + 12000000000L;
            while (System.nanoTime() < deadline) {
                if (ready.isFile()) {
                    JSONObject state =
                            new JSONObject(
                                    new String(bytes(new FileInputStream(ready), 1024), "UTF-8"));
                    int port = state.optInt("port");
                    String secret = state.optString("secret");
                    if (port > 0 && port < 65536 && secret.matches("[a-f0-9]{64}")) {
                        endpoint = "http://127.0.0.1:" + port + "/" + secret;
                        return endpoint;
                    }
                }
                Thread.sleep(40);
            }
            throw new IOException("CatPaw 本机服务启动失败");
        }
    }

    public String resolve(String address) throws Exception {
        URI uri = new URI(address);
        String hash = uri.getHost();
        if (!"catpaw".equals(uri.getScheme())
                || hash == null
                || !hash.matches("[a-f0-9]{64}")
                || uri.getUserInfo() != null
                || uri.getPort() != -1) throw new IOException("CatPaw 本机地址无效");
        for (String part : uri.getPath().split("/"))
            if (part.equals("..") || part.equals(".")) throw new IOException("CatPaw 路径无效");
        if (!new File(new File(root, hash), "index.cjs").isFile())
            throw new IOException("此设备没有对应的 CatPaw 服务包，请重新导入原订阅");
        return start()
                + "/"
                + hash
                + uri.getRawPath()
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    }

    public String read(String address) throws Exception {
        return read(address, "GET");
    }

    public String settings(String address) throws Exception {
        URI logical = new URI(address);
        String raw = read("catpaw://" + logical.getHost() + "/_manage", "POST");
        URI url = new URI(new JSONObject(raw).getString("url"));
        if (!"http".equals(url.getScheme())
                || !"127.0.0.1".equals(url.getHost())
                || !url.getPath().startsWith("/authorize/"))
            throw new IOException("CatPaw 配置页面启动失败");
        return url.toString();
    }

    private String read(String address, String method) throws Exception {
        URI logical = new URI(address);
        HttpURLConnection connection =
                (HttpURLConnection) new URL(resolve(address)).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(35000);
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("CatPaw 线路请求失败（HTTP " + status + "）");
            String raw = new String(bytes(connection.getInputStream(), 16 * 1024 * 1024), "UTF-8");
            return raw.replace(endpoint + "/" + logical.getHost(), "catpaw://" + logical.getHost());
        } finally {
            connection.disconnect();
        }
    }

    public String install(String source) throws Exception {
        if (!isBundle(source)) throw new IOException("没有 CatPaw 服务入口");
        byte[] code = source.getBytes("UTF-8");
        if (code.length > 16 * 1024 * 1024) throw new IOException("CatPaw 服务包超过大小限制");
        StringBuilder digest = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(code))
            digest.append(String.format(Locale.US, "%02x", b & 255));
        String hash = digest.toString();
        File folder = new File(root, hash);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("无法保存 CatPaw 服务包");
        File script = new File(folder, "index.cjs");
        if (!script.exists()) {
            File staging = new File(folder, "index.tmp");
            write(staging, code);
            if (!staging.renameTo(script)) throw new IOException("CatPaw 服务包保存失败");
        }
        JSONObject raw = new JSONObject(read("catpaw://" + hash + "/t4"));
        JSONArray sites = raw.getJSONArray("sites"), valid = new JSONArray();
        for (int i = 0; i < sites.length(); i++) {
            JSONObject site = sites.optJSONObject(i);
            if (site == null || !site.optString("api").startsWith("catpaw://" + hash + "/api/"))
                continue;
            site.put(
                    "key",
                    "paw_" + site.optString("key", new URI(site.getString("api")).getPath()));
            site.put("type", 4);
            valid.put(site);
        }
        if (valid.length() == 0) throw new IOException("CatPaw 没有可用的本机线路");
        return new JSONObject().put("sites", valid).toString();
    }
}
