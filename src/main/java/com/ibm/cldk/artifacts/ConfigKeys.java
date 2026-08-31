package com.ibm.cldk.artifacts;

import com.ibm.cldk.schema.CanId;
import com.ibm.cldk.schema.JArtifact;
import com.ibm.cldk.schema.JConfigKey;
import com.ibm.cldk.schema.Span;
import com.ibm.cldk.schema.Spans;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.yaml.snakeyaml.Yaml;

/**
 * Flattens config-bearing artifacts into {@link JConfigKey} records: pure text-in/records-out,
 * same idiom as {@link ManifestParsers} -- one dispatcher over per-format internals, never
 * throwing. Mirrors codeanalyzer-python's {@code artifacts/config_keys.py}, but narrower: only
 * {@code properties}/{@code yaml}/{@code xml}/{@code dockerfile} plus env-family basenames are
 * handled here (python's {@code json}/{@code toml}/{@code ini} vocabulary has no Java-side
 * artifact format to hang off, per the plan). The {@code xml} flattener is net-new -- python has
 * none to emulate -- and is deliberately the simplest scheme that could work (see {@link
 * #walkXml}).
 *
 * <p>Namespace dispatch: an env-family basename ({@code .env}, {@code .env.*}) always wins,
 * regardless of the artifact's declared {@code format} (matching {@code ArtifactDiscovery}, which
 * assigns these {@code format="text"}); otherwise the {@code format} field selects
 * yaml/xml/dockerfile/properties. Any other format extracts nothing -- not a failure, there is
 * just nothing to flatten.
 *
 * <p>A {@code dockerfile}-format artifact mints TWO namespaces from one file: {@code ENV K=v}
 * directives mint namespace {@code env} (so an {@code os.getenv}-style reader binds to them), and
 * {@code ARG K[=default]} directives mint namespace {@code dockerfile} (build-time only). Both
 * share the bare var name as {@code key}; since {@code ARG X} followed by a promotion idiom like
 * {@code ENV X=$X} is common, the {@code ARG} mint's <em>id</em> (not its {@code key} field) is
 * disambiguated with an {@code arg.} prefix so the two do not collide. A {@code yaml}-format
 * artifact additionally recognizes a compose {@code services.<name>.environment} map and
 * dual-mints those into namespace {@code env} too, on the bare var name, with the same kind of
 * collision guard (an {@code env.} id prefix, since a top-level yaml key could otherwise share a
 * bare name with a recognized env var).
 *
 * <p>{@code value} is populated only when {@code captureValue} is {@code true}; {@code key},
 * {@code namespace}, {@code span}, and {@code references} are extracted unconditionally either
 * way. {@code span} is best-effort and frequently {@code null}: {@code dockerfile}/env-family
 * files are line-oriented so the parse itself knows the exact line, but {@code properties} (parsed
 * via {@link Properties}, which exposes no position info) and {@code yaml}/{@code xml} (tree-shaped)
 * carry no span at all here -- an accepted gap, matching the brief ("best-effort... null is
 * acceptable"; python's own yaml/json/toml spans are already only a regex approximation).
 */
public final class ConfigKeys {

    private ConfigKeys() {}

    /** Flattened keys plus a success flag. {@code ok=false} means a parse failure. */
    public static final class Result {
        public final List<JConfigKey> keys;
        public final boolean ok;

        Result(List<JConfigKey> keys, boolean ok) {
            this.keys = keys;
            this.ok = ok;
        }
    }

    // A parsed leaf before it becomes a JConfigKey: dotted/element/var key, raw value, best-effort span.
    private static final class Entry {
        final String key;
        final Object value;
        final Span span;

        Entry(String key, Object value, Span span) {
            this.key = key;
            this.value = value;
            this.span = span;
        }
    }

    private static final Set<String> ELIGIBLE_FORMATS = Set.of("properties", "yaml", "xml", "dockerfile");

    /**
     * Whether {@code artifact} is worth extracting config keys from: an env-family basename
     * ({@code .env}/{@code .env.*}, regardless of declared format), or one of the namespace-bearing
     * formats. A {@code binary} artifact is never eligible -- there is no decodable text to
     * flatten, and {@code ArtifactDiscovery} downgrades a rule-matched-but-undecodable file to
     * {@code format="binary"} regardless of its basename, so that check wins even over an
     * env-family name. {@code format="gradle"} is deliberately absent: a build script is a
     * program, not a config document (Task 3 already reads its dependencies with a shallow regex);
     * {@code gradle.properties} carries {@code format="properties"} and is where Gradle's actual
     * key-value config lives.
     */
    public static boolean isEligible(JArtifact artifact) {
        String format = artifact.getFormat();
        if ("binary".equals(format)) {
            return false;
        }
        // format != null guards Set.of(...)'s contains(), which throws NPE on a null argument
        // (its open-addressing probe uses null as an internal sentinel) -- format is nullable on a
        // hand-built JArtifact even though real discovery output always sets it.
        return isEnvFamily(basename(artifact.getPath())) || (format != null && ELIGIBLE_FORMATS.contains(format));
    }

    /**
     * Flatten {@code artifact}'s config format into {@link JConfigKey} records, reading {@code
     * text} (the caller's job to supply the real on-disk text, never a possibly-truncated {@code
     * artifact.source} -- see {@link DependencyView#readFromDisk}).
     *
     * <p>Never throws: the entire per-format dispatch is one {@code try}/{@code catch}, so a
     * malformed file (bad YAML, bad XML) degrades to {@code (empty, false)} instead of an
     * exception escaping to the caller -- the same {@code (records, ok)} shape as {@link
     * ManifestParsers}, opposite polarity: {@code ok=true} includes the not-applicable case (a
     * format with no flattener yields {@code (empty, true)}), {@code false} only on a genuine parse
     * failure. The returned list is always sorted by {@code key} -- a dockerfile or dual-minted
     * yaml artifact concatenates its namespace groups before this one sort, and Java's sort is
     * stable, so a same-key tie across namespaces still resolves deterministically.
     */
    public static Result extract(JArtifact artifact, String text, boolean captureValue) {
        String basename = basename(artifact.getPath());
        try {
            List<JConfigKey> keys;
            if (isEnvFamily(basename)) {
                keys = buildKeys(artifact.getId(), "env", parseEnvFile(text), captureValue, false, null);
            } else if ("dockerfile".equals(artifact.getFormat())) {
                keys = new ArrayList<>(buildKeys(
                        artifact.getId(), "env", dockerfileEnvEntries(text), captureValue, true, null));
                keys.addAll(buildKeys(
                        artifact.getId(), "dockerfile", dockerfileArgEntries(text), captureValue, true,
                        k -> "arg." + k));
            } else if ("properties".equals(artifact.getFormat())) {
                keys = buildKeys(artifact.getId(), "properties", parseProperties(text), captureValue, false, null);
            } else if ("yaml".equals(artifact.getFormat())) {
                Object data = new Yaml().load(text);
                List<Entry> flat = flatten(data == null ? new LinkedHashMap<>() : data, "");
                keys = new ArrayList<>(buildKeys(artifact.getId(), "yaml", flat, captureValue, false, null));
                keys.addAll(buildKeys(
                        artifact.getId(), "env", recognizeComposeEnv(flat), captureValue, false,
                        k -> "env." + k));
            } else if ("xml".equals(artifact.getFormat())) {
                keys = extractXml(artifact.getId(), text, captureValue);
            } else {
                return new Result(List.of(), true);
            }
            keys.sort(Comparator.comparing(JConfigKey::getKey));
            return new Result(keys, true);
        } catch (Exception e) {
            return new Result(List.of(), false);
        }
    }

    // ---- properties: java.util.Properties handles key=value/key:value, `\` continuations, and
    // `#`/`!` comments natively -- and last-wins, since a later duplicate key simply overwrites the
    // earlier one on load. Using the JDK parser buys all of that for free, at the cost of position
    // info it does not expose (span stays null; see the class javadoc). ------------------------

    private static List<Entry> parseProperties(String text) throws IOException {
        Properties props = new Properties();
        props.load(new StringReader(text));
        List<Entry> out = new ArrayList<>();
        for (String name : props.stringPropertyNames()) {
            out.add(new Entry(name, props.getProperty(name), null));
        }
        return out;
    }

    // ---- yaml: SnakeYAML `load`, flattened to dotted paths with numeric segments for list
    // indices, plus a supplemental compose-environment recognition pass over the same flattened
    // pairs (see the class javadoc). `new Yaml()` (no custom Constructor) is the hardened default
    // as of SnakeYAML 2.x -- it rejects arbitrary-type tags rather than instantiating them, which
    // matters here because these files are untrusted repository content. ------------------------

    private static List<Entry> flatten(Object obj, String prefix) {
        List<Entry> out = new ArrayList<>();
        if (obj instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) obj).entrySet()) {
                String key = String.valueOf(e.getKey());
                out.addAll(flatten(e.getValue(), prefix.isEmpty() ? key : prefix + "." + key));
            }
        } else if (obj instanceof List<?>) {
            List<?> list = (List<?>) obj;
            for (int i = 0; i < list.size(); i++) {
                out.addAll(flatten(list.get(i), prefix.isEmpty() ? String.valueOf(i) : prefix + "." + i));
            }
        } else {
            out.add(new Entry(prefix, obj, null));
        }
        return out;
    }

    // Compose's `services.<name>.environment` block only (a map of KEY: value, or a list of
    // "KEY=value"/bare "KEY" strings) -- python's k8s `env[].name`/`.value` list-shape recognition
    // is deliberately not ported, the brief names compose only.
    private static final Pattern COMPOSE_ENV = Pattern.compile("^services\\.[^.]+\\.environment\\.(.+)$");

    private static List<Entry> recognizeComposeEnv(List<Entry> flat) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : flat) {
            Matcher m = COMPOSE_ENV.matcher(e.key);
            if (!m.matches()) {
                continue;
            }
            String tail = m.group(1);
            if (ENV_KEY_NAME.matcher(tail).matches()) {
                out.add(new Entry(tail, e.value, null)); // map form: tail IS the var name
            } else if (isDigits(tail)) {
                String s = stringify(e.value); // list form: leaf is "KEY=val" or bare "KEY"
                int eq = s.indexOf('=');
                String key = eq >= 0 ? s.substring(0, eq) : s;
                if (ENV_KEY_NAME.matcher(key).matches()) {
                    out.add(new Entry(key, eq >= 0 ? s.substring(eq + 1) : null, null));
                }
            }
        }
        return out;
    }

    private static boolean isDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    // ---- xml: net-new, python has no XML flattener to emulate. Deliberately the simplest scheme
    // that could work -- see walkXml -- not a richer one (no XPath predicates, no namespace-aware
    // qualified-name handling). Reuses ManifestParsers' hardened DocumentBuilderFactory rather than
    // building a second one: untrusted repository content gets the same XXE hardening either way. -

    private static List<JConfigKey> extractXml(String artifactId, String text, boolean captureValue)
            throws ParserConfigurationException, SAXException, IOException {
        Document doc = ManifestParsers.newSecureDocumentBuilderFactory()
                .newDocumentBuilder()
                .parse(new InputSource(new StringReader(text)));
        Element root = doc.getDocumentElement();
        List<Entry> entries = new ArrayList<>();
        walkXml(root, root.getTagName(), entries);
        return buildKeys(artifactId, "xml", entries, captureValue, false, null);
    }

    /**
     * Element paths, dot-joined by tag name; a numeric segment is added ONLY for a tag name
     * repeated among its siblings (a single occurrence keeps a clean path, e.g. {@code
     * "server.port"} rather than {@code "server.0.port"}). Attributes flatten as {@code "path@attr"}
     * at their own element's path. That is the whole scheme -- kept simple deliberately, per brief.
     */
    private static void walkXml(Element element, String path, List<Entry> out) {
        NamedNodeMap attrs = element.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node attr = attrs.item(i);
            out.add(new Entry(path + "@" + attr.getNodeName(), attr.getNodeValue(), null));
        }
        List<Element> children = directChildElements(element);
        if (children.isEmpty()) {
            out.add(new Entry(path, element.getTextContent().trim(), null));
            return;
        }
        Map<String, List<Element>> byTag = new LinkedHashMap<>();
        for (Element child : children) {
            byTag.computeIfAbsent(child.getTagName(), k -> new ArrayList<>()).add(child);
        }
        for (Map.Entry<String, List<Element>> group : byTag.entrySet()) {
            List<Element> siblings = group.getValue();
            if (siblings.size() == 1) {
                walkXml(siblings.get(0), path + "." + group.getKey(), out);
            } else {
                for (int i = 0; i < siblings.size(); i++) {
                    walkXml(siblings.get(i), path + "." + group.getKey() + "." + i, out);
                }
            }
        }
    }

    private static List<Element> directChildElements(Element parent) {
        List<Element> children = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                children.add((Element) node);
            }
        }
        return children;
    }

    // ---- dockerfile: `ENV`/`ARG` directives. Line-based, case-insensitive instruction keywords
    // (Dockerfile convention is uppercase; the spec itself is not case-sensitive).
    //
    // Two known gaps, carried over from python rather than rediscovered: no BuildKit heredoc
    // awareness (a heredoc body line is just another line that doesn't match ENV/ARG and is
    // silently skipped, same as any other unparseable line); no multi-stage `FROM ... AS` scoping
    // (every ENV/ARG in the file is scanned regardless of which stage it is in). --------------

    private static final Pattern DOCKER_ENV = Pattern.compile("^ENV\\s+(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOCKER_ARG = Pattern.compile("^ARG\\s+(.*)$", Pattern.CASE_INSENSITIVE);

    // Joins a possibly backslash-continued logical instruction line, starting at lines[startIdx].
    // No separator is inserted between joined parts (matching python) -- a space survives only if
    // it was already present before the continuation backslash on the physical line.
    private static final class Joined {
        final String text;
        final int lastIndex;

        Joined(String text, int lastIndex) {
            this.text = text;
            this.lastIndex = lastIndex;
        }
    }

    private static Joined joinContinuations(String[] lines, int startIdx) {
        int i = startIdx;
        StringBuilder joined = new StringBuilder(lines[i].trim());
        while (joined.length() > 0 && joined.charAt(joined.length() - 1) == '\\' && i + 1 < lines.length) {
            joined.setLength(joined.length() - 1); // drop just the continuation backslash
            i++;
            joined.append(lines[i].trim());
        }
        return new Joined(joined.toString(), i);
    }

    /**
     * Whitespace-split {@code s}, except inside a matching quote span (a quoted value may contain
     * spaces) or right after an unquoted {@code \} (a backslash escapes the next character). Quote
     * characters stay IN the returned tokens, stripped afterward by {@link #envValue} so there is
     * one quote-stripping implementation, not two. Direct port of python's {@code
     * _split_ws_respecting_quotes}, for Docker's shell-style {@code ENV} splitting.
     */
    private static List<String> splitWsRespectingQuotes(String s) {
        List<String> tokens = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        Character quote = null;
        int i = 0;
        int n = s.length();
        while (i < n) {
            char ch = s.charAt(i);
            if (quote != null) {
                buf.append(ch);
                if (ch == quote) {
                    quote = null;
                }
            } else if (ch == '\\') {
                i++;
                buf.append(i < n ? s.charAt(i) : ch);
            } else if (ch == '\'' || ch == '"') {
                quote = ch;
                buf.append(ch);
            } else if (Character.isWhitespace(ch)) {
                if (buf.length() > 0) {
                    tokens.add(buf.toString());
                    buf.setLength(0);
                }
            } else {
                buf.append(ch);
            }
            i++;
        }
        if (buf.length() > 0) {
            tokens.add(buf.toString());
        }
        return tokens;
    }

    private static List<Entry> dockerfileEnvEntries(String text) {
        List<Entry> out = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String stripped = lines[i].trim();
            if (stripped.isEmpty() || stripped.startsWith("#") || !DOCKER_ENV.matcher(stripped).matches()) {
                i++;
                continue;
            }
            int startLineno = i + 1;
            Joined joined = joinContinuations(lines, i);
            i = joined.lastIndex;
            // Re-run against the JOINED text (continuation lines add content after "ENV "): still
            // guaranteed to match, since (.*) is greedy to end-of-string regardless of length.
            Matcher dm = DOCKER_ENV.matcher(joined.text);
            dm.matches();
            String rest = dm.group(1).trim();
            Span span = lineSpan(text, lines[startLineno - 1], startLineno);
            String firstToken = rest.isEmpty() ? "" : rest.split("\\s+", 2)[0];
            if (firstToken.contains("=")) {
                // multi-key form: ENV a=1 b=2
                for (String tok : splitWsRespectingQuotes(rest)) {
                    int eq = tok.indexOf('=');
                    if (eq >= 0) {
                        String key = tok.substring(0, eq);
                        if (ENV_KEY_NAME.matcher(key).matches()) {
                            out.add(new Entry(key, envValue(tok.substring(eq + 1)), span));
                        }
                    }
                }
            } else {
                // legacy single-key form: ENV KEY value -- value taken verbatim, no quote processing
                String[] parts = rest.split("\\s+", 2);
                if (parts.length == 2 && ENV_KEY_NAME.matcher(parts[0]).matches()) {
                    out.add(new Entry(parts[0], parts[1], span));
                }
            }
            i++;
        }
        return out;
    }

    private static List<Entry> dockerfileArgEntries(String text) {
        List<Entry> out = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String stripped = lines[i].trim();
            if (stripped.isEmpty() || stripped.startsWith("#") || !DOCKER_ARG.matcher(stripped).matches()) {
                i++;
                continue;
            }
            int startLineno = i + 1;
            Joined joined = joinContinuations(lines, i);
            i = joined.lastIndex;
            // Same re-run-against-the-joined-text guarantee as dockerfileEnvEntries above.
            Matcher dm = DOCKER_ARG.matcher(joined.text);
            dm.matches();
            String rest = dm.group(1).trim();
            Span span = lineSpan(text, lines[startLineno - 1], startLineno);
            int eq = rest.indexOf('=');
            String key = (eq >= 0 ? rest.substring(0, eq) : rest).trim();
            if (ENV_KEY_NAME.matcher(key).matches()) {
                // No "=default" means value=null (distinct from "" for an explicitly empty value).
                out.add(new Entry(key, eq >= 0 ? envValue(rest.substring(eq + 1)) : null, span));
            }
            i++;
        }
        return out;
    }

    // ---- env-family files (.env, .env.*): `KEY=value`, `#` comments, optional `export ` prefix,
    // quote stripping. Shared with dockerfile's ENV/ARG value handling via envValue. -------------

    private static final Pattern ENV_KEY_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Pattern ENV_LINE = Pattern.compile("^(?:export\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.*)$");
    private static final Pattern COMMENT_MARKER = Pattern.compile("\\s#");

    /**
     * The text after {@code KEY=} on one line -> the value. A quoted value ends at its MATCHING
     * closing quote (anything after, including a {@code #}, is a discarded trailing comment); an
     * unquoted value ends at the first unescaped whitespace-then-{@code #} (a bare {@code #} stuck
     * directly to a token is not a comment marker).
     */
    private static String envValue(String raw) {
        raw = raw.trim();
        if (!raw.isEmpty() && (raw.charAt(0) == '\'' || raw.charAt(0) == '"')) {
            char quote = raw.charAt(0);
            int end = raw.indexOf(quote, 1);
            return end != -1 ? raw.substring(1, end) : raw.substring(1);
        }
        Matcher m = COMMENT_MARKER.matcher(raw);
        return (m.find() ? raw.substring(0, m.start()) : raw).trim();
    }

    private static boolean isEnvFamily(String basename) {
        return ".env".equals(basename) || basename.startsWith(".env.");
    }

    private static List<Entry> parseEnvFile(String text) {
        List<Entry> out = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String stripped = lines[i].trim();
            if (stripped.isEmpty() || stripped.startsWith("#")) {
                continue;
            }
            Matcher m = ENV_LINE.matcher(stripped);
            if (!m.matches()) {
                continue;
            }
            out.add(new Entry(m.group(1), envValue(m.group(2)), lineSpan(text, lines[i], i + 1)));
        }
        return out;
    }

    // ---- reference recognition: `${...}` first, masked before the bare `$VAR` scan so `${A}`
    // does not also yield a spurious `$A`. Java's dominant form is a DOTTED property path (Spring
    // placeholder / Maven property, e.g. `${spring.datasource.url}`), unlike a shell env var --
    // hence the braced identifier class allows `.` where the bare one deliberately does not (a
    // shell variable name never contains a dot). No `${{ ... }}` template or `%(name)s`
    // percent-interpolation form: those are python-reference-only vocabulary the brief does not
    // name for Java. --------------------------------------------------------------------------

    private static final Pattern REF_BRACED = Pattern.compile("\\$\\{[A-Za-z_][A-Za-z0-9_.]*\\}");
    private static final Pattern REF_BARE = Pattern.compile("\\$[A-Za-z_][A-Za-z0-9_]*");

    private static final class Ref {
        final int pos;
        final String token;

        Ref(int pos, String token) {
            this.pos = pos;
            this.token = token;
        }
    }

    private static List<String> findReferences(String text) {
        List<Ref> found = new ArrayList<>();
        StringBuilder masked = new StringBuilder(text);
        Matcher braced = REF_BRACED.matcher(text);
        while (braced.find()) {
            found.add(new Ref(braced.start(), braced.group()));
            for (int i = braced.start(); i < braced.end(); i++) {
                masked.setCharAt(i, ' ');
            }
        }
        Matcher bare = REF_BARE.matcher(masked);
        while (bare.find()) {
            found.add(new Ref(bare.start(), bare.group()));
        }
        found.sort(Comparator.comparingInt(r -> r.pos));
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Ref r : found) {
            if (seen.add(r.token)) {
                out.add(r.token);
            }
        }
        return out;
    }

    // ---- shared: coalesce, stringify, span, id ------------------------------------------------

    private static String stringify(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Boolean) { // yaml spells booleans lowercase on disk
            return ((Boolean) value) ? "true" : "false";
        }
        return String.valueOf(value);
    }

    /**
     * Coalesce {@code entries} (last occurrence in file order wins -- e.g. a redefined env var)
     * into {@link JConfigKey} records for one namespace. {@code rawValue=true} (dockerfile) passes
     * the parsed value straight through instead of {@link #stringify}-ing it, so an ARG's absent
     * default surfaces as {@code value=null} rather than {@code stringify}'s {@code ""} for a
     * modeled null. {@code idKey} remaps the dotted/bare key for ID CONSTRUCTION only -- the {@code
     * key} FIELD always stays the bare key; see the class javadoc for why the dockerfile ARG and
     * yaml env-dual-mint call sites need it.
     */
    private static List<JConfigKey> buildKeys(
            String artifactId, String namespace, List<Entry> entries, boolean captureValue,
            boolean rawValue, Function<String, String> idKey) {
        Map<String, Entry> coalesced = new LinkedHashMap<>();
        for (Entry e : entries) {
            coalesced.put(e.key, e);
        }
        List<JConfigKey> out = new ArrayList<>();
        for (Entry e : coalesced.values()) {
            String textValue = rawValue ? (String) e.value : stringify(e.value);
            JConfigKey key = new JConfigKey();
            key.setId(CanId.configKeyId(artifactId, idKey != null ? idKey.apply(e.key) : e.key));
            key.setKey(e.key);
            key.setNamespace(namespace);
            key.setValue(captureValue ? textValue : null);
            key.setSpan(e.span);
            key.setReferences(findReferences(textValue == null ? "" : textValue));
            out.add(key);
        }
        return out;
    }

    // Exact line span (1-based [line,col], matching this schema's JavaParser-native convention --
    // see Span's javadoc), reusing Spans' UTF-8 byte-offset math rather than hand-rolling it.
    private static Span lineSpan(String text, String line, int lineno) {
        Span span = new Span();
        span.setStart(new int[] {lineno, 1});
        span.setEnd(new int[] {lineno, line.length() + 1});
        span.setBytes(Spans.byteOffsets(text, lineno, 0, lineno, line.length()));
        return span;
    }

    private static String basename(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
