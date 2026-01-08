package com.example.gb.util;

import java.net.URI;
import java.util.Locale;

public final class DomPageKeyUtil {

    private DomPageKeyUtil() {}

    public static String originFromUrl(String url) {
        try {
            URI u = URI.create(url);
            return u.getScheme() + "://" + u.getHost() +
                    (u.getPort() > 0 ? ":" + u.getPort() : "");
        } catch (Exception e) {
            return "unknown";
        }
    }

    public static String pageKeyFromUrl(String url) {
        try {
            URI u = URI.create(url);
            String host = u.getHost() + (u.getPort() > 0 ? "_" + u.getPort() : "");
            String path = u.getPath();
            if (path == null || path.equals("/") || path.isBlank()) {
                return host + "__home";
            }
            return host + "__" + path
                    .replaceAll("^/+", "")
                    .replaceAll("/+$", "")
                    .replaceAll("/", "_");
        } catch (Exception e) {
            return "site__page";
        }
    }

    /** host[_port] */
    public static String siteIdFromOrigin(String origin) {
        try {
            URI u = URI.create(origin);
            String host = u.getHost() != null ? u.getHost() : "site";
            int port = u.getPort();
            return (port > 0) ? (host + "_" + port) : host;
        } catch (Exception e) {
            return "site";
        }
    }

    /** / => home, /pricing => pricing */
    public static String pageIdFromUrl(String url) {
        try {
            URI u = URI.create(url);
            String p = (u.getPath() == null || u.getPath().isBlank()) ? "/" : u.getPath();
            if ("/".equals(p)) return "home";

            String norm = p.replaceAll("^/+", "")
                    .replaceAll("/+$", "")
                    .replace("/", "_");
            return slug(norm, "page");
        } catch (Exception e) {
            return "page";
        }
    }


    public static String slug(String s, String fallback) {
        if (s == null || s.isBlank()) return fallback;
        String out = s.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return out.isBlank() ? fallback : out;
    }
}
