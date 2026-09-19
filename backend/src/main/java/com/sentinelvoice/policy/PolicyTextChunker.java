package com.sentinelvoice.policy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Split extracted policy text into stable clause anchors for F6 citations.
 * PDF path: layout-extracted tables become their own chunks; headers/footers already stripped.
 */
final class PolicyTextChunker {

    /** Numbered clause / section title at line start: 12.4, 5.5(a), 1. Purpose — not "1 to 4". */
    static final Pattern CLAUSE_OR_HEADING = Pattern.compile(
            "(?m)^(?:\\s*(?:Section|Clause|Article)\\s+[\\dA-Za-z.\\-]+(?:\\s*[:.\\-]?\\s*.*)?"
                    + "|\\s*\\d+\\.\\d+(?:\\.\\d+)*(?:\\([a-zA-Z]\\))?\\.?\\s+\\S.*"
                    + "|\\s*\\d+\\.\\s+\\S.*"
                    + "|\\s*\\d+\\([a-zA-Z]\\)\\.?\\s+\\S.*)$"
    );

    /** Annexure / section boundaries that must never share a chunk with a prior clause. */
    static final Pattern SECTION_HEADING = Pattern.compile(
            "(?mi)^\\s*(?:Annexure|Annex|Appendix|Schedule|Part)\\s+[A-Za-z0-9.\\-]+.*$"
    );

    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?。])\\s+");
    private static final Pattern BARE_PAGE = Pattern.compile("(?mi)^\\s*page\\s+\\d+\\s*$");
    private static final int TARGET_CHARS = 1200;
    private static final int HEADING_ONLY_MAX = 80;

    record ChunkDraft(
            int ordinal,
            String headingPath,
            String text,
            int charStart,
            int charEnd,
            Integer pageNo,
            boolean table
    ) {
        ChunkDraft withOrdinal(int o) {
            return new ChunkDraft(o, headingPath, text, charStart, charEnd, pageNo, table);
        }
    }

    /** Injection match scoped to a chunk (offsets relative to chunk text). */
    record InjectionFlag(String phrase, int charStart, int charEnd) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("phrase", phrase);
            m.put("charStart", charStart);
            m.put("charEnd", charEnd);
            return m;
        }
    }

    private PolicyTextChunker() {
    }

    static List<ChunkDraft> chunk(String raw) {
        return chunk(raw, false, List.of());
    }

    static List<ChunkDraft> chunk(String raw, boolean pdfMode) {
        return chunk(raw, pdfMode, List.of());
    }

    /**
     * @param tables layout-extracted tables (PDF); each becomes its own chunk at {@code insertAt}
     */
    static List<ChunkDraft> chunk(String raw, boolean pdfMode, List<PdfLayoutExtractor.TableBlock> tables) {
        String text = stripBarePageLines(raw == null ? "" : raw);
        List<ChunkDraft> drafts;
        if (pdfMode && tables != null && !tables.isEmpty()) {
            drafts = chunkWithEmbeddedTables(text, tables);
        } else if (pdfMode) {
            // Heuristic fallback when layout found no tables
            drafts = chunkAroundHeuristicTables(text);
        } else {
            drafts = chunkPlain(text, 0);
        }
        return renumber(mergeHeadingOnly(drafts));
    }

    private static List<ChunkDraft> chunkWithEmbeddedTables(
            String text,
            List<PdfLayoutExtractor.TableBlock> tables
    ) {
        List<PdfLayoutExtractor.TableBlock> ordered = new ArrayList<>(tables);
        ordered.sort(Comparator.comparingInt(PdfLayoutExtractor.TableBlock::insertAt));

        List<ChunkDraft> out = new ArrayList<>();
        int cursor = 0;
        String lastHeading = "";
        for (PdfLayoutExtractor.TableBlock table : ordered) {
            int at = Math.min(Math.max(table.insertAt(), 0), text.length());
            if (at < cursor) {
                at = cursor;
            }
            if (at > cursor) {
                List<ChunkDraft> prose = chunkPlain(text.substring(cursor, at), cursor);
                for (ChunkDraft d : prose) {
                    if (d.headingPath() != null && !d.headingPath().isBlank()) {
                        lastHeading = d.headingPath();
                    }
                    out.add(d);
                }
            }
            String headingBase = table.nearestHeading() != null && !table.nearestHeading().isBlank()
                    ? table.nearestHeading()
                    : lastHeading;
            out.add(new ChunkDraft(
                    out.size(),
                    ensureTableSuffix(headingBase),
                    table.formattedText(),
                    at,
                    at,
                    table.pageNo(),
                    true
            ));
            cursor = at;
        }
        if (cursor < text.length()) {
            out.addAll(chunkPlain(text.substring(cursor), cursor));
        }
        return out;
    }

    private static List<ChunkDraft> chunkAroundHeuristicTables(String text) {
        List<int[]> tables = detectTableRanges(text);
        if (tables.isEmpty()) {
            return chunkPlain(text, 0);
        }
        List<ChunkDraft> out = new ArrayList<>();
        int cursor = 0;
        String lastHeading = "";
        for (int[] range : tables) {
            int start = range[0];
            int end = range[1];
            if (start > cursor) {
                List<ChunkDraft> prose = chunkPlain(text.substring(cursor, start), cursor);
                for (ChunkDraft d : prose) {
                    if (d.headingPath() != null && !d.headingPath().isBlank()) {
                        lastHeading = d.headingPath();
                    }
                    out.add(d);
                }
            }
            String formatted = formatTableBlock(text.substring(start, end));
            if (!formatted.isBlank()) {
                out.add(new ChunkDraft(
                        out.size(),
                        ensureTableSuffix(lastHeading),
                        formatted,
                        start,
                        end,
                        pageAt(text, start),
                        true
                ));
            }
            cursor = end;
        }
        if (cursor < text.length()) {
            out.addAll(chunkPlain(text.substring(cursor), cursor));
        }
        return out;
    }

    /**
     * Lines with ≥3 cells split by wide gaps (2+ spaces / tabs) or pipe columns.
     */
    static List<int[]> detectTableRanges(String text) {
        List<int[]> lineSpans = new ArrayList<>();
        List<Boolean> isTable = new ArrayList<>();
        int i = 0;
        while (i <= text.length()) {
            int nl = text.indexOf('\n', i);
            if (nl < 0) {
                nl = text.length();
            }
            int end = nl;
            String line = text.substring(i, end);
            String trimmed = line.replace("\f", "").strip();
            boolean tableRow = !trimmed.isBlank() && isTableRow(trimmed);
            lineSpans.add(new int[]{i, end < text.length() ? end + 1 : end});
            isTable.add(tableRow);
            if (nl >= text.length()) {
                break;
            }
            i = nl + 1;
        }
        List<int[]> ranges = new ArrayList<>();
        int t = 0;
        while (t < isTable.size()) {
            if (!isTable.get(t)) {
                t++;
                continue;
            }
            int start = lineSpans.get(t)[0];
            int end = lineSpans.get(t)[1];
            int u = t + 1;
            while (u < isTable.size()) {
                if (isTable.get(u)) {
                    end = lineSpans.get(u)[1];
                    u++;
                    continue;
                }
                String mid = text.substring(lineSpans.get(u)[0], lineSpans.get(u)[1]).replace("\f", "").strip();
                if (mid.isEmpty() && u + 1 < isTable.size() && isTable.get(u + 1)) {
                    end = lineSpans.get(u)[1];
                    u++;
                    continue;
                }
                break;
            }
            int rowCount = 0;
            for (int k = t; k < u; k++) {
                if (isTable.get(k)) {
                    rowCount++;
                }
            }
            if (rowCount >= 2) {
                ranges.add(new int[]{start, Math.min(end, text.length())});
            }
            t = Math.max(u, t + 1);
        }
        return ranges;
    }

    static boolean isTableRow(String line) {
        if (line.indexOf('|') >= 0) {
            String[] parts = line.split("\\|");
            int cells = 0;
            for (String p : parts) {
                if (!p.strip().isEmpty()) {
                    cells++;
                }
            }
            if (cells >= 3) {
                return true;
            }
        }
        String[] gapParts = line.split("[ \\t]{2,}");
        int cells = 0;
        for (String p : gapParts) {
            if (!p.strip().isEmpty()) {
                cells++;
            }
        }
        return cells >= 3;
    }

    static String formatTableBlock(String raw) {
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\\R", -1)) {
            String cleaned = line.replace("\f", "").strip();
            if (cleaned.isEmpty()) {
                continue;
            }
            List<String> cells = splitCells(cleaned);
            if (cells.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(String.join(" | ", cells));
        }
        return sb.toString();
    }

    private static List<String> splitCells(String line) {
        List<String> cells = new ArrayList<>();
        if (line.indexOf('|') >= 0) {
            for (String p : line.split("\\|")) {
                String c = p.strip();
                if (!c.isEmpty()) {
                    cells.add(c);
                }
            }
            if (cells.size() >= 3) {
                return cells;
            }
            cells.clear();
        }
        for (String p : line.split("[ \\t]{2,}")) {
            String c = p.strip();
            if (!c.isEmpty()) {
                cells.add(c);
            }
        }
        return cells;
    }

    private static List<ChunkDraft> chunkPlain(String text, int absoluteBase) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Integer> splits = findSplitOffsets(text);
        List<ChunkDraft> out = new ArrayList<>();
        if (splits.size() >= 2) {
            for (int i = 0; i < splits.size(); i++) {
                int start = splits.get(i);
                int end = i + 1 < splits.size() ? splits.get(i + 1) : text.length();
                String segment = text.substring(start, end);
                out.addAll(splitLongSegment(segment, absoluteBase + start, firstLine(segment.strip())));
            }
            return out;
        }
        return splitLongSegment(text, absoluteBase, null);
    }

    /**
     * Split offsets at every numbered clause and every section/annexure heading.
     */
    static List<Integer> findSplitOffsets(String text) {
        List<Integer> offsets = new ArrayList<>();
        offsets.add(0);
        Matcher clause = CLAUSE_OR_HEADING.matcher(text);
        while (clause.find()) {
            int at = clause.start();
            if (at > 0 && !offsets.contains(at)) {
                offsets.add(at);
            }
        }
        Matcher section = SECTION_HEADING.matcher(text);
        while (section.find()) {
            int at = section.start();
            if (at > 0 && !offsets.contains(at)) {
                offsets.add(at);
            }
        }
        offsets.sort(Integer::compareTo);
        return offsets;
    }

    private static List<ChunkDraft> splitLongSegment(String segment, int absoluteBase, String headingHint) {
        String trimmed = segment.strip();
        if (trimmed.isBlank()) {
            return List.of();
        }
        int localStart = indexOfIgnoreFormFeed(segment, trimmed);
        if (localStart < 0) {
            localStart = 0;
        }
        String heading = headingHint != null && !headingHint.isBlank()
                ? headingHint
                : firstLine(trimmed);

        if (trimmed.length() <= TARGET_CHARS) {
            return List.of(new ChunkDraft(
                    0,
                    heading,
                    trimmed,
                    absoluteBase + localStart,
                    absoluteBase + localStart + trimmed.length(),
                    null,
                    false
            ));
        }

        List<ChunkDraft> out = new ArrayList<>();
        int i = 0;
        while (i < trimmed.length()) {
            int end = Math.min(trimmed.length(), i + TARGET_CHARS);
            if (end < trimmed.length()) {
                int breakAt = sentenceAwareBreak(trimmed, i, end);
                end = breakAt;
            }
            String body = trimmed.substring(i, end).strip();
            if (!body.isBlank()) {
                int bodyAt = trimmed.indexOf(body, i);
                if (bodyAt < 0) {
                    bodyAt = i;
                }
                out.add(new ChunkDraft(
                        out.size(),
                        out.isEmpty() ? heading : heading + " (cont.)",
                        body,
                        absoluteBase + localStart + bodyAt,
                        absoluteBase + localStart + bodyAt + body.length(),
                        null,
                        false
                ));
            }
            if (end >= trimmed.length()) {
                break;
            }
            i = end;
        }
        return out;
    }

    private static int sentenceAwareBreak(String text, int from, int proposedEnd) {
        String window = text.substring(from, proposedEnd);
        Matcher m = SENTENCE_END.matcher(window);
        int last = -1;
        while (m.find()) {
            if (m.end() > window.length() / 3) {
                last = m.end();
            }
        }
        if (last > 0) {
            return from + last;
        }
        int nl = text.lastIndexOf('\n', proposedEnd - 1);
        if (nl > from + TARGET_CHARS / 3) {
            return nl + 1;
        }
        return proposedEnd;
    }

    private static int indexOfIgnoreFormFeed(String haystack, String needle) {
        return haystack.indexOf(needle);
    }

    /**
     * Heading-only stub merges into the following chunk (including a table).
     * Tables are never merged into a preceding clause body.
     */
    static List<ChunkDraft> mergeHeadingOnly(List<ChunkDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return List.of();
        }
        List<ChunkDraft> out = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            ChunkDraft cur = drafts.get(i);
            if (i + 1 < drafts.size() && isHeadingOnly(cur) && !cur.table()) {
                ChunkDraft next = drafts.get(i + 1);
                if (next.table()) {
                    String base = cur.headingPath() == null || cur.headingPath().isBlank()
                            ? firstLine(cur.text())
                            : cur.headingPath();
                    out.add(new ChunkDraft(
                            out.size(),
                            ensureTableSuffix(base),
                            next.text(),
                            next.charStart(),
                            next.charEnd(),
                            next.pageNo() != null ? next.pageNo() : cur.pageNo(),
                            true
                    ));
                    i++;
                    continue;
                }
                // Never fold a heading into another numbered clause / annexure chunk.
                if (startsWithBoundary(next.text())) {
                    out.add(cur.withOrdinal(out.size()));
                    continue;
                }
                String mergedText = cur.text().strip() + "\n" + next.text();
                out.add(new ChunkDraft(
                        out.size(),
                        cur.headingPath() != null ? cur.headingPath() : next.headingPath(),
                        mergedText,
                        cur.charStart(),
                        next.charEnd(),
                        next.pageNo() != null ? next.pageNo() : cur.pageNo(),
                        false
                ));
                i++;
            } else {
                ChunkDraft copy = cur;
                if (copy.table()) {
                    copy = new ChunkDraft(
                            out.size(),
                            ensureTableSuffix(copy.headingPath()),
                            copy.text(),
                            copy.charStart(),
                            copy.charEnd(),
                            copy.pageNo(),
                            true
                    );
                } else {
                    copy = copy.withOrdinal(out.size());
                }
                out.add(copy);
            }
        }
        return out;
    }

    private static boolean startsWithBoundary(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String first = firstLine(text.strip());
        if (first == null) {
            return false;
        }
        return CLAUSE_OR_HEADING.matcher(first).matches()
                || SECTION_HEADING.matcher(first).matches();
    }

    static boolean isHeadingOnly(ChunkDraft c) {
        if (c == null || c.table()) {
            return false;
        }
        String t = c.text() == null ? "" : c.text().strip();
        if (t.isEmpty() || t.length() > HEADING_ONLY_MAX) {
            return false;
        }
        return !Pattern.compile("[.!?。]").matcher(t).find();
    }

    private static String ensureTableSuffix(String heading) {
        String h = heading == null ? "" : heading.strip();
        if (h.endsWith("[table]")) {
            return h.isEmpty() ? "[table]" : h;
        }
        if (h.isEmpty() || "[table]".equals(h)) {
            return "[table]";
        }
        return h + " [table]";
    }

    private static List<ChunkDraft> renumber(List<ChunkDraft> drafts) {
        List<ChunkDraft> out = new ArrayList<>(drafts.size());
        for (int i = 0; i < drafts.size(); i++) {
            out.add(drafts.get(i).withOrdinal(i));
        }
        return out;
    }

    private static Integer pageAt(String fullText, int charStart) {
        if (fullText == null || charStart <= 0) {
            return 1;
        }
        int page = 1;
        int limit = Math.min(charStart, fullText.length());
        for (int i = 0; i < limit; i++) {
            if (fullText.charAt(i) == '\f') {
                page++;
            }
        }
        return page;
    }

    private static String firstLine(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        int nl = body.indexOf('\n');
        String line = nl < 0 ? body : body.substring(0, nl);
        line = line.replace("\f", "").strip();
        return line.length() > 160 ? line.substring(0, 160) : line;
    }

    static String stripBarePageLines(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : input.split("\\R", -1)) {
            if (BARE_PAGE.matcher(line).matches()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb.toString();
    }

    static String stripControlChars(String input) {
        if (input == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t' || c == '\f' || c >= 0x20) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static String detectLanguageHeuristic(String text) {
        if (text == null || text.isBlank()) {
            return "und";
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x0900 && c <= 0x097F) {
                return "hi";
            }
        }
        return "en";
    }

    /**
     * Longest-match injection phrases with char ranges relative to {@code text}.
     * Overlapping matches keep only the longest.
     */
    static List<InjectionFlag> scanInjectionFlags(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        String[] phrases = {
                "ignore all previous instructions",
                "ignore previous instructions",
                "ignore all previous",
                "disregard the above",
                "disregard previous",
                "you are now",
                "system prompt",
                "jailbreak",
                "developer mode"
        };
        List<InjectionFlag> candidates = new ArrayList<>();
        for (String p : phrases) {
            int from = 0;
            while (from < lower.length()) {
                int idx = lower.indexOf(p, from);
                if (idx < 0) {
                    break;
                }
                candidates.add(new InjectionFlag(p, idx, idx + p.length()));
                from = idx + 1;
            }
        }
        candidates.sort(Comparator
                .comparingInt((InjectionFlag f) -> f.phrase().length()).reversed()
                .thenComparingInt(InjectionFlag::charStart));

        List<InjectionFlag> kept = new ArrayList<>();
        for (InjectionFlag c : candidates) {
            boolean overlaps = false;
            for (InjectionFlag k : kept) {
                if (c.charStart() < k.charEnd() && c.charEnd() > k.charStart()) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) {
                kept.add(c);
            }
        }
        kept.sort(Comparator.comparingInt(InjectionFlag::charStart));
        return kept;
    }

    /** Phrases only (legacy helper). */
    static List<String> scanInjectionPhrases(String text) {
        List<String> out = new ArrayList<>();
        for (InjectionFlag f : scanInjectionFlags(text)) {
            out.add(f.phrase());
        }
        return out;
    }
}
