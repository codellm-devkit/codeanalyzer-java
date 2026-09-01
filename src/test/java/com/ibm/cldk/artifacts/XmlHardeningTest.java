package com.ibm.cldk.artifacts;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * The XML parser reads untrusted repository content, so its hardening is load-bearing and each
 * guarantee is asserted separately rather than trusted as a bundle.
 *
 * <p>An internal DOCTYPE subset is permitted on purpose: refusing every DOCTYPE rejected the whole
 * document rather than the dangerous part, discarding 43 real files on ThingsBoard v4.0 (35 of them
 * {@code logback.xml}). These tests pin that the permission did not buy an XXE back — every
 * external reference must still fail to resolve.
 */
class XmlHardeningTest {

    private static Document parse(String xml) throws ParserConfigurationException, SAXException, IOException {
        return ManifestParsers.newConfigDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    @Test
    void anInternalDoctypeSubsetIsAccepted() throws Exception {
        // The logback.xml shape: a DOCTYPE that references nothing outside the document.
        Document d = parse("<!DOCTYPE configuration>\n<configuration><root level=\"INFO\"/></configuration>");
        assertNotNull(d.getDocumentElement());
        assertTrue("configuration".equals(d.getDocumentElement().getTagName()));
    }

    @Test
    void anExternalEntityDoesNotLeakFileContents(@TempDir Path tmp) throws Exception {
        Path secret = tmp.resolve("secret.txt");
        Files.write(secret, "TOP_SECRET_VALUE".getBytes("UTF-8"));
        String xml = "<!DOCTYPE r [<!ENTITY xxe SYSTEM \"" + secret.toUri() + "\">]>\n<r>&xxe;</r>";

        String text;
        try {
            text = parse(xml).getDocumentElement().getTextContent();
        } catch (SAXException blocked) {
            return; // Refusing outright is an equally acceptable outcome.
        }
        assertFalse(text.contains("TOP_SECRET_VALUE"),
                "external entity must never resolve — this is the XXE the hardening exists to stop");
    }

    @Test
    void anExternalParameterEntityIsNotFetched(@TempDir Path tmp) throws Exception {
        Path dtd = tmp.resolve("evil.dtd");
        Files.write(dtd, "<!ENTITY x \"PWNED\">".getBytes("UTF-8"));
        String xml = "<!DOCTYPE r [<!ENTITY % ext SYSTEM \"" + dtd.toUri() + "\"> %ext;]>\n<r>ok</r>";
        try {
            assertFalse(parse(xml).getDocumentElement().getTextContent().contains("PWNED"),
                    "external parameter entity must not be fetched");
        } catch (SAXException blocked) {
            // Also fine.
        }
    }

    @Test
    void anExternalDtdIsNotLoaded(@TempDir Path tmp) throws Exception {
        Path dtd = tmp.resolve("ext.dtd");
        Files.write(dtd, "<!ENTITY injected \"INJECTED\">".getBytes("UTF-8"));
        String xml = "<!DOCTYPE r SYSTEM \"" + dtd.toUri() + "\">\n<r>ok</r>";
        try {
            assertFalse(parse(xml).getDocumentElement().getTextContent().contains("INJECTED"),
                    "an external DTD must not be loaded");
        } catch (SAXException blocked) {
            // Also fine.
        }
    }

    @Test
    void aBillionLaughsExpansionIsRefusedRatherThanExhaustingMemory() {
        StringBuilder dtd = new StringBuilder("<!DOCTYPE lolz [<!ENTITY lol \"lol\">");
        for (int i = 1; i <= 9; i++) {
            dtd.append("<!ENTITY lol").append(i).append(" \"");
            for (int j = 0; j < 10; j++) {
                dtd.append("&lol").append(i - 1 == 0 ? "" : String.valueOf(i - 1)).append(";");
            }
            dtd.append("\">");
        }
        dtd.append("]>\n<lolz>&lol9;</lolz>");
        // FEATURE_SECURE_PROCESSING caps entity expansion, so this fails fast instead of hanging.
        assertThrows(SAXException.class, () -> parse(dtd.toString()),
                "unbounded entity expansion must be refused");
    }

    @Test
    void theParserDoesNotWriteToStderrOnAMalformedDocument() throws Exception {
        java.io.PrintStream original = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(captured, true, "UTF-8"));
        try {
            assertThrows(SAXException.class, () -> parse("<a><unclosed></a>"));
        } finally {
            System.setErr(original);
        }
        assertTrue(captured.toString("UTF-8").isEmpty(),
                "the parser must not narrate to stderr — errors travel in the result, not as [Fatal Error] spam");
    }
}
