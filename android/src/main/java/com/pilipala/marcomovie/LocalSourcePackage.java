package com.pilipala.marcomovie;

import java.io.*;
import java.net.URI;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Copies an imported package into its own immutable directory after validating its entry point. */
final class LocalSourcePackage {
    final Cms.Config config;
    final String origin;

    private LocalSourcePackage(File root, File entry) throws Exception {
        origin = Cms.fileUri(entry);
        try (InputStream in = new FileInputStream(entry)) {
            config = Cms.parseConfig(Cms.read(in, Cms.MAX_JSON), origin);
        }
        checkLocal(root, config.spider);
        for (Cms.Site site : config.sites) {
            checkLocal(root, site.api);
            checkLocal(root, site.jar);
            if (site.ext instanceof String) checkLocal(root, (String) site.ext);
        }
    }

    private static void checkLocal(File root, String address) throws Exception {
        if (address == null || !address.startsWith("file:")) return;
        File resource = new File(new URI(address.split(";md5;", 2)[0])).getCanonicalFile();
        if (!resource.getPath().startsWith(root.getCanonicalPath() + File.separator)
                || !resource.isFile()) throw new IOException("本地包缺少资源或引用了包外文件");
    }

    private static int rank(File entry) {
        List<String> names =
                Arrays.asList("config.json", "tv.json", "api.json", "index.json", "main.json");
        int index = names.indexOf(entry.getName().toLowerCase(Locale.ROOT));
        return index < 0 ? names.size() : index;
    }

    static synchronized LocalSourcePackage open(File filesDir, byte[] bytes) throws Exception {
        if (bytes.length > 28 * 1024 * 1024) throw new IOException("本地包超过 28 MB");
        File store = new File(filesDir, "source-packages");
        if (!store.isDirectory() && !store.mkdir()) throw new IOException("无法创建本地包目录");
        StringBuilder digest = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
            digest.append(String.format(Locale.ROOT, "%02x", b & 255));
        File installed = new File(store, digest.toString());
        File stage = new File(store, "import-" + UUID.randomUUID());
        if (!stage.mkdir()) throw new IOException("无法准备本地包");
        List<File> entries = new ArrayList<>();
        try {
            String prefix = stage.getCanonicalPath() + File.separator;
            long size = 0;
            int count = 0;
            try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                byte[] buffer = new byte[8192];
                for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                    String name = entry.getName().replace('\\', '/');
                    if (++count > 2048
                            || name.startsWith("/")
                            || name.contains(":")
                            || Arrays.asList(name.split("/")).contains(".."))
                        throw new IOException("本地包目录或文件数量无效");
                    File dest = new File(stage, name).getCanonicalFile();
                    if (!dest.getPath().startsWith(prefix)) throw new IOException("本地包路径无效");
                    if (entry.isDirectory()) continue;
                    File parent = dest.getParentFile();
                    if ((!parent.isDirectory() && !parent.mkdirs()) || !dest.createNewFile())
                        throw new IOException("本地包文件重复或路径无效");
                    try (OutputStream out = new FileOutputStream(dest)) {
                        for (int n; (n = in.read(buffer)) != -1; ) {
                            size += n;
                            if (size > 48L * 1024 * 1024) throw new IOException("本地包解压超过 48 MB");
                            out.write(buffer, 0, n);
                        }
                    }
                    if (name.toLowerCase(Locale.ROOT).endsWith(".json")) entries.add(dest);
                }
            }
            Collections.sort(entries, (a, b) -> Integer.compare(rank(a), rank(b)));
            if (entries.isEmpty()
                    || entries.size() > 1 && rank(entries.get(0)) == rank(entries.get(1)))
                throw new IOException("本地包需要唯一的 JSON 入口，例如 config.json");
            File entry = entries.get(0);
            String relative = stage.toURI().relativize(entry.toURI()).getPath();
            new LocalSourcePackage(stage, entry);
            if (!installed.isDirectory() && !stage.renameTo(installed))
                throw new IOException("无法保存本地包");
            return new LocalSourcePackage(installed, new File(installed, relative));
        } finally {
            discard(stage);
        }
    }

    private static void discard(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) discard(child);
        file.delete();
    }
}
