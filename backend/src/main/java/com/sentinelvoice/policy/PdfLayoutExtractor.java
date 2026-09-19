package com.sentinelvoice.policy;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Position-aware PDF extraction: strip repeating headers/footers and bare page labels,
 * detect columnar tables, and emit cleaned prose plus discrete table blocks.
 * <p>
 * Reading order comes from PDFTextStripper (gap-aware). Positions are used to classify
 * header/footer bands and columnar table rows so those lines can be removed from prose.
 */
final class PdfLayoutExtractor {

    private static final Logger log = LoggerFactory.getLogger(PdfLayoutExtractor.class);

    private static final int MAX_PAGES = 200;
    private static final float TOP_BAND = 0.08f;
    private static final float BOTTOM_BAND = 0.08f;
    private static final float Y_LINE_TOL = 3.0f;
    private static final float GAP_FACTOR = 2.2f;
    private static final Pattern PAGE_LABEL = Pattern.compile("(?i)^\\s*page\\s+\\d+\\s*$");
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    record TableBlock(int pageNo, String nearestHeading, String formattedText, int insertAt) {
    }

    record Result(String text, Integer pageCount, List<TableBlock> tables) {
    }

    private PdfLayoutExtractor() {
    }

    static Result extract(byte[] bytes) throws Exception {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            if (doc.isEncrypted()) {
                throw new PolicyDocumentException(
                        "ENCRYPTED_PDF",
                        "Encrypted PDFs are not supported — remove the password and re-upload"
                );
            }
            int pages = doc.getNumberOfPages();
            if (pages > MAX_PAGES) {
                throw new PolicyDocumentException(
                        "TOO_MANY_PAGES",
                        "PDF exceeds " + MAX_PAGES + " pages (got " + pages + ")"
                );
            }

            List<PageModel> models = new ArrayList<>();
            for (int p = 1; p <= pages; p++) {
                models.add(buildPage(doc, p));
            }

            String rawText = joinRawText(models);
            int rawChars = rawText.length();
            int imageCount = countImages(doc);
            log.info(
                    "policy_pdf_extract rawChars={} pageCount={} imageCount={}",
                    rawChars,
                    pages,
                    imageCount
            );

            assertNotScanned(rawChars, pages, imageCount);

            // Repeated header/footer stripping only for multi-page docs (>= 3).
            boolean stripChrome = pages >= 3;
            Set<String> repeating = stripChrome ? findRepeatingKeys(models) : Set.of();
            AssembleOutcome assembled = assemble(models, repeating, stripChrome);

            String cleaned = PolicyTextChunker.stripControlChars(assembled.text()).trim();
            String rawClean = PolicyTextChunker.stripControlChars(rawText).trim();
            int rawLen = rawClean.length();
            int cleanedLen = cleaned.length();
            if (rawLen > 0 && cleanedLen < rawLen * 0.4) {
                log.warn(
                        "policy_pdf_strip_reverted rawChars={} cleanedChars={} removedPct={}",
                        rawLen,
                        cleanedLen,
                        Math.round(100.0 * (1.0 - (cleanedLen / (double) rawLen)))
                );
                cleaned = rawClean;
                // Tables from the stripped pass are still usable; prose came from raw.
            }

            if (cleaned.isBlank()) {
                throw new PolicyDocumentException(
                        "EMPTY_TEXT",
                        "Document contained no extractable text"
                );
            }
            return new Result(cleaned, pages, assembled.tables());
        } catch (InvalidPasswordException ex) {
            throw new PolicyDocumentException(
                    "ENCRYPTED_PDF",
                    "Encrypted PDFs are not supported — remove the password and re-upload"
            );
        }
    }

    /**
     * Scanned decision uses RAW text before any header/footer stripping.
     * Scanned only when short-text criteria hold AND the PDF contains images.
     * A one-page document is never marked scanned solely because its text is short.
     */
    private static void assertNotScanned(int rawChars, int pages, int imageCount) {
        boolean shortTotal = rawChars < 50;
        boolean shortMultiPage = pages >= 2 && (rawChars / (double) pages) < 20.0;
        boolean shortEnough = shortTotal || shortMultiPage;
        if (!shortEnough || imageCount <= 0) {
            return;
        }
        // One-page + short text without the multi-page average rule: still require images
        // (already true here). Explicitly documented: never mark 1-page scanned for short
        // text alone — that path returns early when imageCount == 0 above.
        throw new PolicyDocumentException(
                "SCANNED_PDF",
                "scanned/image PDF — OCR not enabled"
        );
    }

    private static String joinRawText(List<PageModel> models) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < models.size(); i++) {
            if (i > 0) {
                sb.append('\f');
            }
            PageModel page = models.get(i);
            for (int li = 0; li < page.readingLines.size(); li++) {
                if (li > 0) {
                    sb.append('\n');
                }
                sb.append(page.readingLines.get(li));
            }
        }
        return sb.toString();
    }

    private static int countImages(PDDocument doc) throws IOException {
        int total = 0;
        for (PDPage page : doc.getPages()) {
            total += countImagesInResources(page.getResources(), new HashSet<>());
        }
        return total;
    }

    private static int countImagesInResources(PDResources resources, Set<PDResources> seen)
            throws IOException {
        if (resources == null || !seen.add(resources)) {
            return 0;
        }
        int n = 0;
        for (var name : resources.getXObjectNames()) {
            PDXObject xo = resources.getXObject(name);
            if (xo instanceof PDImageXObject) {
                n++;
            } else if (xo instanceof PDFormXObject form) {
                n += countImagesInResources(form.getResources(), seen);
            }
        }
        return n;
    }

    private static PageModel buildPage(PDDocument doc, int pageNo) throws IOException {
        PDPage page = doc.getPage(pageNo - 1);
        float pageHeight = Math.max(page.getMediaBox().getHeight(), 1f);

        PositionCollector positions = new PositionCollector(pageNo, pageHeight);
        positions.setStartPage(pageNo);
        positions.setEndPage(pageNo);
        positions.setSortByPosition(true);
        positions.getText(doc);
        List<PosLine> posLines = positions.toLines();

        GapAwarePdfTextStripper stripper = new GapAwarePdfTextStripper();
        stripper.setStartPage(pageNo);
        stripper.setEndPage(pageNo);
        stripper.setSortByPosition(true);
        String raw = stripper.getText(doc);
        List<String> readingLines = new ArrayList<>();
        for (String line : raw.split("\\R", -1)) {
            String t = line.strip();
            if (!t.isEmpty()) {
                readingLines.add(t);
            }
        }

        return new PageModel(pageNo, pageHeight, readingLines, posLines);
    }

    /**
     * Keys that appear on ≥50% of pages. Only called when page count ≥ 3.
     * A line that appears on a single page is never treated as repeating.
     */
    private static Set<String> findRepeatingKeys(List<PageModel> pages) {
        if (pages.size() < 3) {
            return Set.of();
        }
        Map<String, Set<Integer>> pagesByKey = new HashMap<>();
        for (PageModel page : pages) {
            Set<String> seen = new HashSet<>();
            for (String line : page.readingLines) {
                String key = normalizeKey(line);
                if (key.isBlank() || !seen.add(key)) {
                    continue;
                }
                pagesByKey.computeIfAbsent(key, k -> new HashSet<>()).add(page.pageNo);
            }
        }
        int threshold = Math.max(2, (int) Math.ceil(pages.size() * 0.5));
        Set<String> repeating = new HashSet<>();
        for (Map.Entry<String, Set<Integer>> e : pagesByKey.entrySet()) {
            if (e.getValue().size() >= threshold) {
                repeating.add(e.getKey());
            }
        }
        return repeating;
    }

    private static AssembleOutcome assemble(
            List<PageModel> pages, Set<String> repeating, boolean stripChrome
    ) {
        StringBuilder prose = new StringBuilder();
        List<TableBlock> tables = new ArrayList<>();
        String lastHeading = "";

        for (int pi = 0; pi < pages.size(); pi++) {
            if (pi > 0) {
                prose.append('\f');
            }
            PageModel page = pages.get(pi);
            Set<String> dropExact = new HashSet<>();
            if (stripChrome) {
                for (PosLine pl : page.posLines) {
                    if (pl.yNorm >= 1.0f - TOP_BAND || pl.yNorm <= BOTTOM_BAND) {
                        dropExact.add(pl.text.strip());
                    }
                }
            }

            List<List<String>> tableGroups = new ArrayList<>();
            Set<String> tableKeys = new HashSet<>();
            int i = 0;
            while (i < page.posLines.size()) {
                int end = i;
                while (end < page.posLines.size() && page.posLines.get(end).cells.size() >= 3) {
                    end++;
                }
                if (end - i >= 2) {
                    List<String> group = new ArrayList<>();
                    for (int k = i; k < end; k++) {
                        PosLine pl = page.posLines.get(k);
                        group.add(String.join(" | ", pl.cells));
                        tableKeys.add(normalizeKey(pl.text));
                    }
                    tableGroups.add(group);
                    i = end;
                } else {
                    i = Math.max(i + 1, end);
                }
            }

            boolean[] emitted = new boolean[tableGroups.size()];
            for (String line : page.readingLines) {
                String stripped = line.strip();
                if (stripped.isEmpty()) {
                    continue;
                }
                if (stripChrome && PAGE_LABEL.matcher(stripped).matches()) {
                    continue;
                }
                String key = normalizeKey(stripped);
                if (stripChrome && (repeating.contains(key) || dropExact.contains(stripped))) {
                    continue;
                }

                int matchedGroup = indexOfMatchingGroup(tableGroups, stripped, key);
                if (matchedGroup >= 0 || tableKeys.contains(key) || isTableRowText(stripped)) {
                    if (matchedGroup >= 0 && !emitted[matchedGroup]) {
                        tables.add(new TableBlock(
                                page.pageNo,
                                lastHeading,
                                String.join("\n", tableGroups.get(matchedGroup)),
                                prose.length()
                        ));
                        emitted[matchedGroup] = true;
                    } else if (matchedGroup < 0) {
                        for (int g = 0; g < tableGroups.size(); g++) {
                            if (!emitted[g] && groupMatchesLine(tableGroups.get(g), stripped)) {
                                tables.add(new TableBlock(
                                        page.pageNo,
                                        lastHeading,
                                        String.join("\n", tableGroups.get(g)),
                                        prose.length()
                                ));
                                emitted[g] = true;
                                break;
                            }
                        }
                    }
                    continue;
                }

                if (isHeadingLine(stripped)) {
                    lastHeading = truncate(stripped, 160);
                }
                if (prose.length() > 0 && prose.charAt(prose.length() - 1) != '\f') {
                    prose.append('\n');
                }
                prose.append(stripped);
            }

            for (int g = 0; g < tableGroups.size(); g++) {
                if (!emitted[g]) {
                    tables.add(new TableBlock(
                            page.pageNo,
                            lastHeading,
                            String.join("\n", tableGroups.get(g)),
                            prose.length()
                    ));
                }
            }
        }

        return new AssembleOutcome(prose.toString(), tables);
    }

    private static int indexOfMatchingGroup(List<List<String>> groups, String stripped, String key) {
        for (int g = 0; g < groups.size(); g++) {
            if (groupMatchesLine(groups.get(g), stripped) || groupContainsKey(groups.get(g), key)) {
                return g;
            }
        }
        return -1;
    }

    private static boolean groupContainsKey(List<String> group, String key) {
        for (String row : group) {
            if (normalizeKey(row).equals(key) || normalizeKey(row.replace(" | ", "  ")).equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static boolean groupMatchesLine(List<String> group, String stripped) {
        if (group.isEmpty()) {
            return false;
        }
        String first = group.get(0);
        String key = normalizeKey(stripped);
        if (normalizeKey(first).equals(key)) {
            return true;
        }
        String asGaps = first.replace(" | ", "  ");
        return stripped.equals(asGaps) || normalizeKey(asGaps).equals(key);
    }

    private static boolean isTableRowText(String line) {
        return PolicyTextChunker.isTableRow(line);
    }

    private static boolean isHeadingLine(String text) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty() || t.length() > 160) {
            return false;
        }
        return PolicyTextChunker.CLAUSE_OR_HEADING.matcher(t).matches()
                || PolicyTextChunker.SECTION_HEADING.matcher(t).matches();
    }

    private static String normalizeKey(String text) {
        if (text == null) {
            return "";
        }
        return DIGITS.matcher(text.toLowerCase(Locale.ROOT).strip()).replaceAll("").replaceAll("\\s+", " ").strip();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private record AssembleOutcome(String text, List<TableBlock> tables) {
    }

    private record PosLine(int pageNo, float yNorm, String text, List<String> cells) {
    }

    private record PageModel(
            int pageNo,
            float pageHeight,
            List<String> readingLines,
            List<PosLine> posLines
    ) {
    }

    private static final class PositionCollector extends PDFTextStripper {
        private final int pageNo;
        private final float pageHeight;
        private final List<Word> words = new ArrayList<>();

        PositionCollector(int pageNo, float pageHeight) throws IOException {
            this.pageNo = pageNo;
            this.pageHeight = pageHeight;
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) {
            if (textPositions == null || textPositions.isEmpty()) {
                return;
            }
            for (TextPosition pos : textPositions) {
                String u = pos.getUnicode();
                if (u == null || u.isBlank()) {
                    continue;
                }
                words.add(new Word(
                        pos.getXDirAdj(),
                        pos.getEndX(),
                        pos.getYDirAdj(),
                        pos.getWidthOfSpace(),
                        u
                ));
            }
        }

        List<PosLine> toLines() {
            if (words.isEmpty()) {
                return List.of();
            }
            words.sort((a, b) -> {
                int yCmp = Float.compare(b.y, a.y);
                if (Math.abs(a.y - b.y) > Y_LINE_TOL) {
                    return yCmp;
                }
                return Float.compare(a.x, b.x);
            });

            List<List<Word>> lineWords = new ArrayList<>();
            List<Word> cur = new ArrayList<>();
            float curY = words.get(0).y;
            for (Word w : words) {
                if (!cur.isEmpty() && Math.abs(w.y - curY) > Y_LINE_TOL) {
                    lineWords.add(cur);
                    cur = new ArrayList<>();
                    curY = w.y;
                }
                cur.add(w);
                curY = (curY * (cur.size() - 1) + w.y) / cur.size();
            }
            if (!cur.isEmpty()) {
                lineWords.add(cur);
            }
            if (lineWords.size() >= 2) {
                float yFirst = meanY(lineWords.get(0));
                float yLast = meanY(lineWords.get(lineWords.size() - 1));
                if (yFirst < yLast) {
                    java.util.Collections.reverse(lineWords);
                }
            }

            List<PosLine> lines = new ArrayList<>();
            for (List<Word> lw : lineWords) {
                lw.sort((a, b) -> Float.compare(a.x, b.x));
                List<String> cells = splitCells(lw);
                StringBuilder sb = new StringBuilder();
                Word prev = null;
                for (Word w : lw) {
                    if (prev != null) {
                        float gap = w.x - prev.endX;
                        float spaceW = Math.max(prev.spaceWidth, 2f);
                        if (gap > spaceW * GAP_FACTOR) {
                            sb.append("  ");
                        } else if (gap > spaceW * 0.35f) {
                            sb.append(' ');
                        }
                    }
                    sb.append(w.unicode);
                    prev = w;
                }
                String text = sb.toString().strip();
                if (text.isEmpty()) {
                    continue;
                }
                float yNorm = meanY(lw) / pageHeight;
                lines.add(new PosLine(pageNo, yNorm, text, cells));
            }
            return lines;
        }

        private static List<String> splitCells(List<Word> lw) {
            List<String> cells = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            Word prev = null;
            for (Word w : lw) {
                if (prev != null) {
                    float gap = w.x - prev.endX;
                    float spaceW = Math.max(prev.spaceWidth, 2f);
                    if (gap > spaceW * GAP_FACTOR) {
                        String c = cell.toString().strip();
                        if (!c.isEmpty()) {
                            cells.add(c);
                        }
                        cell.setLength(0);
                    } else if (gap > spaceW * 0.35f) {
                        cell.append(' ');
                    }
                }
                cell.append(w.unicode);
                prev = w;
            }
            String last = cell.toString().strip();
            if (!last.isEmpty()) {
                cells.add(last);
            }
            return cells;
        }

        private static float meanY(List<Word> lw) {
            float sum = 0f;
            for (Word w : lw) {
                sum += w.y;
            }
            return sum / Math.max(lw.size(), 1);
        }

        private record Word(float x, float endX, float y, float spaceWidth, String unicode) {
        }
    }
}
