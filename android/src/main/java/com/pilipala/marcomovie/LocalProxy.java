package com.pilipala.marcomovie;

import android.app.Application;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Independent loopback-only implementation of the observed CSP HTTP response ABI. */
public final class LocalProxy implements Closeable {
    static boolean loopbackHost(String host) {
        return ProxyAddress.isLoopbackHost(host);
    }

    final Application app;
    final Cms.Config config;
    final ServerSocket server;
    final String token = UUID.randomUUID().toString().replace("-", "");
    final ThreadPoolExecutor requests =
            new ThreadPoolExecutor(4, 32, 60, TimeUnit.SECONDS, new SynchronousQueue<Runnable>());
    volatile boolean closed;

    public LocalProxy(Application application, Cms.Config source) throws IOException {
        app = application;
        config = source;
        server = bind();
        ProxyAddress.port = server.getLocalPort();
        ProxyAddress.token = token;
        Thread accept =
                new Thread(
                        () -> {
                            while (!closed)
                                try {
                                    Socket socket = server.accept();
                                    try {
                                        requests.execute(() -> serve(socket));
                                    } catch (RejectedExecutionException full) {
                                        socket.close();
                                    }
                                } catch (IOException e) {
                                    if (!closed) close();
                                }
                        },
                        "movie-loopback-proxy");
        accept.setDaemon(true);
        accept.start();
    }

    static ServerSocket bind() throws IOException {
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 9978));
            return socket;
        } catch (BindException occupied) {
            socket.close();
            ServerSocket fallback = new ServerSocket();
            try {
                fallback.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
                return fallback;
            } catch (IOException failure) {
                fallback.close();
                throw failure;
            }
        } catch (IOException error) {
            socket.close();
            throw error;
        }
    }

    static String address() {
        return ProxyAddress.base();
    }

    /** Only the CSP host's reserved /proxy URLs are relocated, never arbitrary loopback services. */
    static String rewrite(String value) {
        return ProxyAddress.rewrite(value, ProxyAddress.port, ProxyAddress.token);
    }

    static String line(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > limit) throw new IOException("HTTP line too large");
        }
        return b.toString("ISO-8859-1");
    }

    private static long[] parseContentRange(String contentRange) {
        if (contentRange == null) return null;
        // e.g. "bytes 0-1048575/4582918231"
        String s = contentRange.trim();
        if (!s.regionMatches(true, 0, "bytes ", 0, 6)) return null;
        int slash = s.indexOf('/');
        String rangePart = (slash > 6 ? s.substring(6, slash) : s.substring(6)).trim();
        String totalPart = slash > 6 ? s.substring(slash + 1).trim() : "*";
        int dash = rangePart.indexOf('-');
        if (dash <= 0 || dash >= rangePart.length() - 1) return null;
        try {
            long start = Long.parseLong(rangePart.substring(0, dash).trim());
            long end = Long.parseLong(rangePart.substring(dash + 1).trim());
            long total = "*".equals(totalPart) ? -1L : Long.parseLong(totalPart);
            if (end >= start && start >= 0) return new long[] {start, end, total};
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    private static long parseClientRequestedEnd(String rangeHeader) {
        if (rangeHeader == null) return -1L;
        String s = rangeHeader.trim();
        if (!s.regionMatches(true, 0, "bytes=", 0, 6)) return -1L;
        int dash = s.indexOf('-', 6);
        if (dash < 0 || dash >= s.length() - 1) return -1L;
        String endPart = s.substring(dash + 1).trim();
        int comma = endPart.indexOf(',');
        if (comma >= 0) endPart = endPart.substring(0, comma).trim();
        if (endPart.isEmpty()) return -1L;
        try {
            return Long.parseLong(endPart);
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    void serve(Socket socket) {
        try (Socket connection = socket) {
            if (connection.getInetAddress() == null
                    || !connection.getInetAddress().isLoopbackAddress()) return;
            connection.setSoTimeout(30000);
            InputStream in = connection.getInputStream();
            OutputStream out = connection.getOutputStream();
            String[] request = line(in, 8192).split(" ");
            if (request.length < 2) return;
            String method = request[0];
            String rawTarget = request[1];
            int qAt = rawTarget.indexOf('?');
            String path = qAt >= 0 ? rawTarget.substring(0, qAt) : rawTarget;
            String query = qAt >= 0 ? rawTarget.substring(qAt + 1) : null;
            if (!"/proxy".equals(path) || (!method.equals("GET") && !method.equals("HEAD"))) {
                simple(out, 404, "Not found");
                return;
            }
            Map<String, String> headers = new LinkedHashMap<>();
            Map<String, String> params = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            int size = 0;
            for (; ; ) {
                String h = line(in, 8192);
                size += h.length();
                if (size > 32768) throw new IOException("Headers too large");
                if (h.isEmpty()) break;
                int at = h.indexOf(':');
                if (at > 0) {
                    String rawKey = h.substring(0, at).trim();
                    String lowerKey = rawKey.toLowerCase(Locale.ROOT);
                    String val = h.substring(at + 1).trim();
                    headers.put(lowerKey, val);
                    params.put(rawKey, val);
                }
            }
            if (query != null) {
                for (String pair : query.split("&")) {
                    if (pair.isEmpty()) continue;
                    String[] p = pair.split("=", 2);
                    try {
                        params.put(
                                URLDecoder.decode(p[0], "UTF-8"),
                                p.length == 2 ? URLDecoder.decode(p[1], "UTF-8") : "");
                    } catch (Exception ignored) {
                        params.put(p[0], p.length == 2 ? p[1] : "");
                    }
                }
            }
            String requestToken = params.remove("_mm_token");
            boolean isJs = "js".equalsIgnoreCase(params.get("do"));
            if (!ProxyAddress.isAllowedRequest(
                    headers.get("host"),
                    headers.get("origin"),
                    headers.get("sec-fetch-site"),
                    requestToken,
                    token,
                    isJs)) {
                simple(out, 403, "Forbidden");
                return;
            }
            String upstreamOrigin = headers.remove("x-movie-origin");
            if (upstreamOrigin != null && !upstreamOrigin.isEmpty()) {
                headers.put("origin", upstreamOrigin);
                params.put("Origin", upstreamOrigin);
            }
            String upstreamHost = headers.remove("x-movie-host");
            if (upstreamHost != null && !upstreamHost.isEmpty()) {
                params.put("Host", upstreamHost);
            }
            if (headers.containsKey("range")) {
                headers.put("Range", headers.get("range"));
            }
            if (headers.containsKey("user-agent")) {
                headers.put("User-Agent", headers.get("user-agent"));
            }
            if (headers.containsKey("cookie")) {
                headers.put("Cookie", headers.get("cookie"));
            }
            if (headers.containsKey("referer")) {
                headers.put("Referer", headers.get("referer"));
            }
            params.put("request-headers", new JSONObject(headers).toString());
            Object[] response;
            try {
                response = isJs ? JsEngine.proxyFor(params) : CspEngine.proxy(app, config, params);
            } catch (Throwable e) {
                AppLog.w("LocalProxy", "source proxy failed", e);
                simple(out, 502, "Source proxy failed");
                return;
            }
            if (response == null || response.length < 3 || !(response[0] instanceof Number)) {
                simple(out, 502, "Invalid source response");
                return;
            }
            int code = ((Number) response[0]).intValue();
            if (code < 100 || code > 599) code = 502;
            String mime = String.valueOf(response[1]);
            byte[] fixedBytes = null;
            InputStream body;
            if (response[2] instanceof InputStream) {
                body = (InputStream) response[2];
            } else if (response[2] instanceof byte[]) {
                fixedBytes = (byte[]) response[2];
                body = new ByteArrayInputStream(fixedBytes);
            } else {
                fixedBytes =
                        response[2] == null
                                ? new byte[0]
                                : response[2].toString().getBytes("UTF-8");
                body = new ByteArrayInputStream(fixedBytes);
            }

            long contentLength = fixedBytes != null ? fixedBytes.length : -1L;
            String contentRange = null;
            boolean hasAcceptRanges = false;
            Map<String, String> extraHeaders = new LinkedHashMap<>();
            if (response.length > 3 && response[3] instanceof Map) {
                for (Object item : ((Map<?, ?>) response[3]).entrySet()) {
                    Map.Entry<?, ?> e = (Map.Entry<?, ?>) item;
                    String k = String.valueOf(e.getKey());
                    String v = String.valueOf(e.getValue());
                    if (!safe(k) || !safe(v)) continue;
                    if (k.equalsIgnoreCase("Content-Length")) {
                        try {
                            long parsed = Long.parseLong(v.trim());
                            if (parsed >= 0) contentLength = parsed;
                        } catch (NumberFormatException ignored) {
                        }
                    } else if (k.equalsIgnoreCase("Content-Range")) {
                        contentRange = v.trim();
                        extraHeaders.put("Content-Range", contentRange);
                    } else if (k.equalsIgnoreCase("Accept-Ranges")) {
                        hasAcceptRanges = true;
                        extraHeaders.put("Accept-Ranges", v.trim());
                    } else if (!k.equalsIgnoreCase("Transfer-Encoding")
                            && !k.equalsIgnoreCase("Connection")
                            && !k.equalsIgnoreCase("Content-Type")) {
                        extraHeaders.put(k, v);
                    }
                }
            }
            long[] cr = parseContentRange(contentRange);
            long clientReqEnd = parseClientRequestedEnd(headers.get("range"));
            long desiredEnd = -1L;
            boolean chainChunks = false;
            if (cr != null) {
                if (contentLength < 0) {
                    contentLength = (cr[1] - cr[0]) + 1L;
                }
                if (code == 206 && cr[2] > 0) {
                    desiredEnd =
                            clientReqEnd >= cr[0]
                                    ? Math.min(clientReqEnd, cr[2] - 1L)
                                    : (cr[2] - 1L);
                    if (cr[1] < desiredEnd) {
                        chainChunks = true;
                        extraHeaders.put(
                                "Content-Range", "bytes " + cr[0] + "-" + desiredEnd + "/" + cr[2]);
                        contentLength = (desiredEnd - cr[0]) + 1L;
                    }
                }
            }

            try (InputStream payload = body) {
                StringBuilder head =
                        new StringBuilder(
                                "HTTP/1.1 "
                                        + code
                                        + (code == 206
                                                ? " Partial Content"
                                                : code == 200 ? " OK" : " Response")
                                        + "\r\nConnection: close\r\n");
                if (safe(mime)) head.append("Content-Type: ").append(mime).append("\r\n");
                if (!hasAcceptRanges && (code == 200 || code == 206)) {
                    head.append("Accept-Ranges: bytes\r\n");
                }
                for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                    head.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
                }
                boolean useChunked = contentLength < 0;
                if (useChunked) {
                    head.append("Transfer-Encoding: chunked\r\n\r\n");
                } else {
                    head.append("Content-Length: ").append(contentLength).append("\r\n\r\n");
                }
                out.write(head.toString().getBytes("ISO-8859-1"));
                if (!method.equals("HEAD")) {
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    if (useChunked) {
                        while ((n = payload.read(buffer)) != -1) {
                            if (n == 0) continue;
                            out.write((Integer.toHexString(n) + "\r\n").getBytes("US-ASCII"));
                            out.write(buffer, 0, n);
                            out.write(new byte[] {13, 10});
                        }
                        out.write("0\r\n\r\n".getBytes("US-ASCII"));
                    } else {
                        long remaining = contentLength;
                        long currentOffset = cr != null ? cr[0] : 0L;
                        while (remaining > 0
                                && (n =
                                                payload.read(
                                                        buffer,
                                                        0,
                                                        (int) Math.min(buffer.length, remaining)))
                                        != -1) {
                            if (n == 0) continue;
                            out.write(buffer, 0, n);
                            remaining -= n;
                            currentOffset += n;
                        }
                        while (chainChunks && remaining > 0 && !closed) {
                            String nextRange =
                                    "bytes="
                                            + currentOffset
                                            + "-"
                                            + (clientReqEnd >= currentOffset ? clientReqEnd : "");
                            headers.put("range", nextRange);
                            headers.put("Range", nextRange);
                            params.put("Range", nextRange);
                            params.put("range", nextRange);
                            params.put("request-headers", new JSONObject(headers).toString());
                            Object[] nextResp =
                                    isJs
                                            ? JsEngine.proxyFor(params)
                                            : CspEngine.proxy(app, config, params);
                            if (nextResp == null
                                    || nextResp.length < 3
                                    || !(nextResp[2] instanceof InputStream)) {
                                break;
                            }
                            long chunkRead = 0L;
                            try (InputStream nextStream = (InputStream) nextResp[2]) {
                                while (remaining > 0
                                        && (n =
                                                        nextStream.read(
                                                                buffer,
                                                                0,
                                                                (int)
                                                                        Math.min(
                                                                                buffer.length,
                                                                                remaining)))
                                                != -1) {
                                    if (n == 0) continue;
                                    out.write(buffer, 0, n);
                                    remaining -= n;
                                    currentOffset += n;
                                    chunkRead += n;
                                }
                            }
                            if (chunkRead <= 0L) break;
                        }
                    }
                }
                out.flush();
            }
        } catch (Exception ignored) {
        }
    }

    static boolean safe(String s) {
        return s != null && s.indexOf('\r') < 0 && s.indexOf('\n') < 0;
    }

    static void simple(OutputStream o, int status, String message) throws IOException {
        byte[] b = message.getBytes("UTF-8");
        o.write(
                ("HTTP/1.1 "
                                + status
                                + " Response\r\nContent-Type: text/plain; charset=utf-8\r\nConnection: close\r\nContent-Length: "
                                + b.length
                                + "\r\n\r\n")
                        .getBytes("US-ASCII"));
        o.write(b);
        o.flush();
    }

    public void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {
        }
        requests.shutdownNow();
    }
}
