package me.cortex.voxy.client.core.gl.shader;


import net.caffeinemc.mods.sodium.client.gl.shader.ShaderConstants;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderParser;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NeoForge-compatible shader loader for Voxy.
 *
 * On Fabric, Sodium's ShaderLoader.getShaderSource() uses a flat classloader that can
 * access all mod resources. On NeoForge, each mod has an isolated classloader, so
 * Sodium's classloader cannot access Voxy's shader resources.
 *
 * This loader bypasses Sodium's resource loading and uses Voxy's own classloader.
 *
 * Upstream reference: https://github.com/MCRcortex/voxy
 * See: src/main/java/me/cortex/voxy/client/core/gl/shader/ShaderLoader.java
 */
public class ShaderLoader {
    private static final Pattern IMPORT_PATTERN = Pattern.compile("#import <(?<namespace>.*):(?<path>.*)>");

    /**
     * Parse and load a shader while preserving the shader's own #version.
     */
    public static String parse(String id) {
        // Load shader source using Voxy's classloader (NeoForge classloader isolation fix)
        String shaderSource = getShaderSource(id);

        // Process any nested #import directives recursively
        shaderSource = processImports(shaderSource);

        // Apply Sodium's shader constants processing (handles #define etc.)
        String processed = parseShaderSource(shaderSource);

        return processed.replaceAll("\r\n", "\n").stripLeading();
    }

    private static String parseShaderSource(String shaderSource) {
        try {
            Object parsed = ShaderParser.class
                    .getMethod("parseShader", String.class, ShaderConstants.class)
                    .invoke(null, shaderSource, ShaderConstants.builder().build());
            if (parsed instanceof String source) {
                return source;
            }
            return (String) parsed.getClass().getMethod("src").invoke(parsed);
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            throw new RuntimeException("Failed to parse shader source with Sodium", e);
        }
    }

    /**
     * Load shader source using Voxy's classloader.
     * Path format: "namespace:path" -> "/assets/{namespace}/shaders/{path}"
     */
    private static String getShaderSource(String id) {
        String[] parts = id.split(":", 2);
        String namespace = parts.length > 1 ? parts[0] : "voxy";
        String path = parts.length > 1 ? parts[1] : parts[0];

        String resourcePath = String.format("/assets/%s/shaders/%s", namespace, path);

        try (InputStream in = ShaderLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new RuntimeException("Shader not found: " + resourcePath + " (id=" + id + ")");
            }
            return IOUtils.toString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read shader source: " + resourcePath, e);
        }
    }

    /**
     * Process #import directives recursively, loading from Voxy's resources.
     */
    private static String processImports(String source) {
        StringBuilder result = new StringBuilder();
        for (String line : source.split("\n")) {
            if (line.trim().startsWith("#import")) {
                Matcher matcher = IMPORT_PATTERN.matcher(line.trim());
                if (matcher.matches()) {
                    String namespace = matcher.group("namespace");
                    String path = matcher.group("path");
                    String importId = namespace + ":" + path;
                    String importedSource = getShaderSource(importId);
                    result.append(processImports(importedSource));
                } else {
                    result.append(line);
                }
            } else {
                result.append(line);
            }
            result.append("\n");
        }
        return result.toString();
    }
}
