package com.ibm.cldk.artifacts;

import com.ibm.cldk.schema.CanId;
import com.ibm.cldk.schema.JConfigKey;
import com.ibm.cldk.utils.Log;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Extracts normalized {@code config_key} facts from structured config files. A flat {@code .properties}
 * key and a nested {@code .yml} path share one dotted key space, so both flatten to the same canonical
 * dotted form ({@code spring.datasource.url}) — a downstream config-use join sees one namespace.
 *
 * <p>Parsing is a pure overlay: a document whose structure cannot be understood must never prevent its
 * artifact (with raw text) from being inventoried, so every entry point catches and logs, returning an
 * empty map on failure.
 */
final class ConfigKeyParser {

    private ConfigKeyParser() {}

    /** {@code ${...}} placeholder, capturing the inner reference (e.g. {@code PAYMENT_HOST} or
     * {@code spring.datasource.url:default}). */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    /** Dispatch by recognized format; unknown formats yield no keys (the artifact is still inventoried). */
    static Map<String, JConfigKey> parse(String artifactId, String format, byte[] bytes, String relPath) {
        if (format == null) {
            return new LinkedHashMap<>();
        }
        try {
            switch (format) {
                case "properties":
                    return parseProperties(artifactId, bytes);
                case "yaml":
                    return parseYaml(artifactId, bytes);
                default:
                    return new LinkedHashMap<>();
            }
        } catch (Exception e) {
            Log.warn("Could not parse config keys from " + relPath + " (" + e.getMessage()
                    + "); the artifact is still inventoried with its raw text");
            return new LinkedHashMap<>();
        }
    }

    private static Map<String, JConfigKey> parseProperties(String artifactId, byte[] bytes) {
        // Do NOT use java.util.Properties: it discards order and cannot round-trip, and we want a
        // deterministic, order-preserving walk. Parse line-oriented key=value / key:value ourselves.
        Map<String, JConfigKey> keys = new LinkedHashMap<>();
        String text = new String(bytes, StandardCharsets.UTF_8);
        for (String rawLine : text.split("\n", -1)) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                continue;
            }
            int sep = firstUnescapedSeparator(line);
            if (sep < 0) {
                continue;
            }
            String key = line.substring(0, sep).strip();
            String value = line.substring(sep + 1).strip();
            if (key.isEmpty()) {
                continue;
            }
            putKey(keys, artifactId, key, value);
        }
        return keys;
    }

    private static Map<String, JConfigKey> parseYaml(String artifactId, byte[] bytes) {
        Map<String, JConfigKey> keys = new LinkedHashMap<>();
        LoaderOptions options = new LoaderOptions();
        Yaml yaml = new Yaml(options);
        // A YAML file may hold multiple documents (--- separators); flatten each.
        for (Object doc : yaml.loadAll(new ByteArrayInputStream(bytes))) {
            flatten(keys, artifactId, "", doc);
        }
        return keys;
    }

    /** Recursively flatten a parsed YAML/JSON value into dotted keys. Maps recurse by key; lists index
     * by ordinal ({@code servers[0].host}); scalars become a leaf config key. */
    @SuppressWarnings("unchecked")
    private static void flatten(Map<String, JConfigKey> keys, String artifactId, String prefix, Object node) {
        if (node instanceof Map) {
            for (Map.Entry<Object, Object> e : ((Map<Object, Object>) node).entrySet()) {
                String childKey = String.valueOf(e.getKey());
                String dotted = prefix.isEmpty() ? childKey : prefix + "." + childKey;
                flatten(keys, artifactId, dotted, e.getValue());
            }
        } else if (node instanceof List) {
            List<Object> list = (List<Object>) node;
            for (int i = 0; i < list.size(); i++) {
                flatten(keys, artifactId, prefix + "[" + i + "]", list.get(i));
            }
        } else {
            // Scalar leaf (including null). Store the boxed literal as parsed.
            putKeyValue(keys, artifactId, prefix, node);
        }
    }

    /** Add a key whose value is a raw string (properties): keep references, box the string value. */
    private static void putKey(Map<String, JConfigKey> keys, String artifactId, String key, String value) {
        putKeyValue(keys, artifactId, key, value);
    }

    private static void putKeyValue(
            Map<String, JConfigKey> keys, String artifactId, String key, Object value) {
        if (key.isEmpty() || keys.containsKey(key)) {
            return;
        }
        JConfigKey ck = new JConfigKey();
        ck.setId(CanId.configKeyId(artifactId, key));
        ck.setKey(key);
        String namespace = namespaceOf(key);
        if (namespace != null) {
            ck.setNamespace(namespace);
        }
        ck.setValue(value);
        ck.setReferences(referencesOf(value));
        keys.put(key, ck);
    }

    /** The leading dotted segment as the shared key-space namespace ({@code spring}, {@code env}, …);
     * null for a single-segment key with no namespace. */
    private static String namespaceOf(String key) {
        int dot = key.indexOf('.');
        return dot > 0 ? key.substring(0, dot) : null;
    }

    /** Placeholder references preserved where recognizable — {@code ${PAYMENT_HOST}} →
     * {@code env:PAYMENT_HOST}. Only string values carry placeholders. */
    private static List<String> referencesOf(Object value) {
        List<String> refs = new ArrayList<>();
        if (!(value instanceof String)) {
            return refs;
        }
        Matcher m = PLACEHOLDER.matcher((String) value);
        while (m.find()) {
            String inner = m.group(1).strip();
            // A property placeholder may carry a `:default` suffix — the reference is the part before it.
            int colon = inner.indexOf(':');
            String ref = colon > 0 ? inner.substring(0, colon).strip() : inner;
            refs.add("env:" + ref);
        }
        return refs;
    }

    /** Index of the first unescaped {@code =} or {@code :} separator on a properties line. */
    private static int firstUnescapedSeparator(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\') {
                i++; // skip escaped char
                continue;
            }
            if (c == '=' || c == ':') {
                return i;
            }
        }
        return -1;
    }
}
