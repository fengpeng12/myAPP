package com.pilipala.marcomovie;

import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.util.zip.*;
import java.nio.charset.StandardCharsets;

public final class LocalPackageChecks {
    private static byte[] zip(String... pairs) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < pairs.length; i += 2) {
                zip.putNextEntry(new ZipEntry(pairs[i]));
                zip.write(pairs[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        String config =
                "{\"sites\":[{\"key\":\"test\",\"type\":3,\"api\":\"./modules/test.mjs\",\"ext\":\"./rules.txt\"}]}";
        byte[] valid =
                zip(
                        "nested/config.json",
                        config,
                        "nested/modules/test.mjs",
                        "export default {}",
                        "nested/rules.txt",
                        "ok");
        LocalSourcePackage imported = LocalSourcePackage.open(root, valid);
        Cms.Site reopened = Cms.parseConfig(imported.config.raw, imported.origin).sites.get(0);
        File module = new File(new URI(reopened.api));
        if (!reopened.api.startsWith("file:///")
                || !module.isFile()
                || !new File(new URI((String) reopened.ext)).isFile())
            throw new AssertionError("packaged resources cannot be reopened");
        for (byte[] invalid :
                new byte[][] {
                    zip("config.json", config),
                    zip("../escape", "bad"),
                    zip("a.json", config, "b.json", config)
                }) {
            try {
                LocalSourcePackage.open(root, invalid);
                throw new AssertionError("invalid package accepted");
            } catch (IOException expected) {
            }
            if (!module.isFile()) throw new AssertionError("previous package was removed");
        }
        if (!LocalSourcePackage.open(root, valid).origin.equals(imported.origin))
            throw new AssertionError("unstable package id");
        System.out.println(
                "PASS reopen nested package, reject missing resource, traversal and ambiguous entry, retain previous import, reuse identical package");
    }
}
