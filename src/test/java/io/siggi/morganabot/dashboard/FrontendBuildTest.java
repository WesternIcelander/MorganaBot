package io.siggi.morganabot.dashboard;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The frontend that {@code mvn package} builds into {@code web/} on the classpath: the page, its styles and the
 * compiled modules are there, every file the page and the modules load exists, and the page has nothing inline that
 * the dashboard's CSP would block.
 */
class FrontendBuildTest {
    private static final ClassLoader LOADER = FrontendBuildTest.class.getClassLoader();
    private static final Pattern PAGE_REFS = Pattern.compile("(?:src|href)=\"([^\"]+)\"");
    private static final Pattern IMPORTS = Pattern.compile("(?:import|export)[^\"';]*?from\\s*\"(\\./[^\"]+)\"|import\\s*\"(\\./[^\"]+)\"");

    private static String read(String resource) throws IOException {
        try (InputStream in = LOADER.getResourceAsStream(resource)) {
            assertNotNull(in, resource + " is missing from the classpath (is the frontend built?)");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void pageAndEverythingItLoadsIsBuilt() throws IOException {
        StaticFiles files = new StaticFiles(LOADER, "web");
        String html = read(files.resourceFor("/dashboard/"));
        assertFalse(html.contains("<style"), "no inline styles (CSP)");
        assertFalse(html.contains("style=\""), "no inline styles (CSP)");
        assertFalse(Pattern.compile("<script(?![^>]*\\ssrc=)").matcher(html).find(), "no inline scripts (CSP)");

        List<String> refs = new ArrayList<>();
        Matcher matcher = PAGE_REFS.matcher(html);
        while (matcher.find()) refs.add(matcher.group(1));
        assertTrue(refs.contains("style.css"));
        assertTrue(refs.contains("js/main.js"));
        for (String ref : refs) {
            String resource = files.resourceFor("/dashboard/" + ref);
            assertNotNull(resource, ref + " isn't a servable path");
            read(resource);
        }
    }

    @Test
    void everyModuleImportResolves() throws IOException {
        List<String> queue = new ArrayList<>(List.of("web/js/main.js"));
        List<String> seen = new ArrayList<>();
        while (!queue.isEmpty()) {
            String module = queue.remove(0);
            if (seen.contains(module)) continue;
            seen.add(module);
            String source = read(module);
            String dir = module.substring(0, module.lastIndexOf('/') + 1);
            Matcher matcher = IMPORTS.matcher(source);
            while (matcher.find()) {
                String ref = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                assertTrue(ref.endsWith(".js"), module + " imports " + ref + " without .js, which browsers can't load");
                queue.add(dir + ref.substring(2));
            }
        }
        assertTrue(seen.contains("web/js/http.js"));
        assertTrue(seen.contains("web/js/stream.js"));
        for (String page : List.of("settings", "streamers", "audit", "problems", "admin-ui", "template-editor", "diagnose", "audit-text", "preview", "sidebar", "system", "super-text", "blocked")) {
            assertTrue(seen.contains("web/js/" + page + ".js"), page + ".js isn't loaded by the shell");
        }
    }
}
