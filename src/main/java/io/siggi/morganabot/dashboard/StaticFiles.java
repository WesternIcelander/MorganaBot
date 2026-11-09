package io.siggi.morganabot.dashboard;

import io.siggi.http.HTTPRequest;
import io.siggi.http.HTTPServer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * Serves the dashboard's frontend from the classpath: {@code /dashboard/} is {@code web/index.html}, and
 * {@code /dashboard/<path>} is {@code web/<path>}. Only known file types are served, and paths with {@code ..},
 * hidden names or backslashes are refused. Responses carry the page security headers ({@link WebResponses#PAGE_CSP})
 * and {@code Cache-Control: no-cache}, so a new jar's files are picked up straight away.
 */
public final class StaticFiles {
    public static final String BASE = "/dashboard";

    static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("map", "application/json; charset=utf-8"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("webp", "image/webp"),
            Map.entry("woff2", "font/woff2")
    );

    private final ClassLoader loader;
    private final String root;

    /**
     * @param root the classpath directory, e.g. {@code web}
     */
    public StaticFiles(ClassLoader loader, String root) {
        this.loader = loader;
        this.root = root.endsWith("/") ? root.substring(0, root.length() - 1) : root;
    }

    /**
     * Registers {@code /dashboard} and everything under it. Register this <b>after</b> the login and API
     * responders: SiggiHTTP's registry picks the first match, and this one matches every dashboard path.
     */
    @SuppressWarnings("deprecation")
    public void register(HTTPServer server) {
        server.responderRegistry.register(BASE, this::respond, true, true);
    }

    /**
     * The classpath resource for a request path, or null if it isn't one this serves.
     */
    String resourceFor(String path) {
        if (path.equals(BASE + "/")) return root + "/index.html";
        if (!path.startsWith(BASE + "/")) return null;
        String relative = path.substring(BASE.length() + 1);
        if (relative.isEmpty() || relative.contains("\\") || relative.contains("\0")) return null;
        for (String segment : relative.split("/", -1)) {
            if (segment.isEmpty() || segment.startsWith(".")) return null;
        }
        if (type(relative) == null) return null;
        return root + "/" + relative;
    }

    static String type(String name) {
        int slash = name.lastIndexOf('/');
        int dot = name.lastIndexOf('.');
        if (dot <= slash + 1) return null;
        return TYPES.get(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    void respond(HTTPRequest request) throws Exception {
        request.allowedMethods("GET");
        String path = request.path();
        if (path.equals(BASE)) {
            WebResponses.pageHeaders(request.response, "no-store");
            request.response.redirect(BASE + "/");
            return;
        }
        String resource = resourceFor(path);
        byte[] bytes = null;
        if (resource != null) {
            try (InputStream in = loader.getResourceAsStream(resource)) {
                if (in != null) bytes = in.readAllBytes();
            } catch (IOException e) {
                bytes = null;
            }
        }
        if (bytes == null) {
            notFound(request);
            return;
        }
        request.response.setHeader("200 OK");
        WebResponses.pageHeaders(request.response, "no-cache");
        request.response.setContentType(type(resource));
        request.response.contentLength(bytes.length);
        request.response.write(bytes);
    }

    private static void notFound(HTTPRequest request) throws IOException {
        byte[] body = ("<!doctype html><html><head><meta charset=\"utf-8\"><title>Not found</title></head>"
                + "<body><h1>Not found</h1><p>There's nothing here.</p></body></html>").getBytes(StandardCharsets.UTF_8);
        request.response.setHeader("404 Not Found");
        WebResponses.pageHeaders(request.response, "no-store");
        request.response.setContentType("text/html; charset=utf-8");
        request.response.contentLength(body.length);
        request.response.write(body);
    }
}
