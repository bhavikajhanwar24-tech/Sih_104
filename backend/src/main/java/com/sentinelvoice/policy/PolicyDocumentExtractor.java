package com.sentinelvoice.policy;

import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.xml.sax.ContentHandler;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Content sniffing (Tika) + extraction (PDFBox / Tika / plain text).
 */
@Component
public class PolicyDocumentExtractor {

    private static final Logger log = LoggerFactory.getLogger(PolicyDocumentExtractor.class);
    public static final String MIME_PDF = "application/pdf";
    public static final String MIME_DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    public static final String MIME_PLAIN = "text/plain";

    private static final Set<String> ALLOWED_DETECTED = Set.of(MIME_PDF, MIME_DOCX, MIME_PLAIN);

    /** Extension (lowercase, no dot) → MIME type implied by the filename. */
    private static final Map<String, String> EXT_TO_MIME = Map.of(
            "pdf", MIME_PDF,
            "docx", MIME_DOCX,
            "txt", MIME_PLAIN,
            "md", MIME_PLAIN
    );

    private final Tika tika = new Tika();

    /**
     * Detect MIME from file bytes only (no filename hint).
     */
    public String sniffMime(byte[] bytes) {
        try {
            return normaliseMime(tika.detect(bytes));
        } catch (Exception ex) {
            throw new PolicyDocumentException("CONTENT_TYPE_DETECT_FAILED", "Could not detect file type");
        }
    }

    /**
     * Require extension ∈ {pdf,docx,txt,md} and that Tika-detected type matches the extension's type.
     * On mismatch throws {@code MIME_MISMATCH} (HTTP 415). Stores nothing — caller must not persist.
     */
    public void assertExtensionMatchesDetected(String filename, String detectedMime) {
        String ext = extensionOf(filename);
        String expected = EXT_TO_MIME.get(ext);
        if (expected == null) {
            throw new PolicyDocumentException(
                    "UNSUPPORTED_TYPE",
                    "Only PDF, DOCX, TXT, and MD are allowed (extension ." + (ext.isEmpty() ? "?" : ext) + ")"
            );
        }
        String detected = normaliseMime(detectedMime);
        if (!expected.equals(detected)) {
            throw new PolicyDocumentException(
                    "MIME_MISMATCH",
                    "File content (" + detected + ") does not match its extension (." + ext + ")"
            );
        }
        if (!ALLOWED_DETECTED.contains(detected)) {
            throw new PolicyDocumentException(
                    "UNSUPPORTED_TYPE",
                    "Only PDF, DOCX, TXT, and MD are allowed (got " + detected + ")"
            );
        }
    }

    public static boolean isPaginatedMime(String mime) {
        return MIME_PDF.equals(normaliseMime(mime));
    }

    public ExtractionResult extract(byte[] bytes, String mime) {
        String normalised = normaliseMime(mime);
        try {
            if (MIME_PDF.equals(normalised)) {
                return extractPdf(bytes);
            }
            if (MIME_DOCX.equals(normalised)) {
                return extractViaTika(bytes);
            }
            // TXT / MD (stored as text/plain)
            String text = new String(bytes, StandardCharsets.UTF_8);
            text = PolicyTextChunker.stripControlChars(text);
            if (text.isBlank()) {
                throw new PolicyDocumentException("EMPTY_TEXT", "Document contained no extractable text");
            }
            return new ExtractionResult(text, null, List.of());
        } catch (PolicyDocumentException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("policy_extract_failed mime={} cause={}", mime, ex.toString());
            throw new PolicyDocumentException(
                    "EXTRACTION_FAILED",
                    ex.getMessage() == null ? "extraction failed" : ex.getMessage()
            );
        }
    }

    private ExtractionResult extractPdf(byte[] bytes) throws Exception {
        PdfLayoutExtractor.Result layout = PdfLayoutExtractor.extract(bytes);
        return new ExtractionResult(layout.text(), layout.pageCount(), layout.tables());
    }

    private ExtractionResult extractViaTika(byte[] bytes) throws Exception {
        AutoDetectParser parser = new AutoDetectParser();
        ContentHandler handler = new BodyContentHandler(-1);
        Metadata metadata = new Metadata();
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            parser.parse(in, handler, metadata, new ParseContext());
        }
        String text = PolicyTextChunker.stripControlChars(handler.toString()).trim();
        if (text.isBlank()) {
            throw new PolicyDocumentException("EMPTY_TEXT", "Document contained no extractable text");
        }
        return new ExtractionResult(text, null, List.of());
    }

    static String normaliseMime(String mime) {
        if (mime == null || mime.isBlank()) {
            return "";
        }
        return mime.toLowerCase(Locale.ROOT).split(";")[0].trim();
    }

    static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        String name = filename;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public record ExtractionResult(
            String text,
            Integer pageCount,
            List<PdfLayoutExtractor.TableBlock> tables
    ) {
        public ExtractionResult {
            tables = tables == null ? List.of() : List.copyOf(tables);
        }
    }
}
