package com.sentinelvoice.forensics;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import com.sentinelvoice.forensics.model.EvidenceItem;
import com.sentinelvoice.forensics.model.ForensicDossier;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.springframework.stereotype.Component;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Court-ready PDF renderer for {@link ForensicDossier} (OpenPDF + JFreeChart). LGPL — not iText 7 AGPL.
 *
 * <p>Document SHA-256 is embedded in every page footer via a fixed-width placeholder window.
 * {@link ForensicDossierService#documentSha256(byte[])} hashes the file <em>excluding</em> that
 * window so the printed digest equals the audit-anchored digest of the delivered bytes.
 */
@Component
public class DossierPdfRenderer {

    /** ASCII marker immediately preceding the 64-char hex digest window in the PDF footer. */
    public static final String SHA_MARKER = "SVSHA256=";
    /** Exactly 64 bytes reserved for the hex digest (US-ASCII). */
    public static final int SHA_WINDOW = 64;
    public static final String SHA_PLACEHOLDER = " ".repeat(SHA_WINDOW);

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    private static final Color NAVY = new Color(0x0B, 0x1F, 0x3A);
    private static final Color ACCENT = new Color(0x1A, 0x56, 0x7A);
    private static final Color RULE = new Color(0xC5, 0xCD, 0xD6);

    private final Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 22, NAVY);
    private final Font headingFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, NAVY);
    private final Font bodyFont = FontFactory.getFont(FontFactory.HELVETICA, 9, Color.DARK_GRAY);
    private final Font smallFont = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.GRAY);
    private final Font monoFont = FontFactory.getFont(FontFactory.COURIER, 7, Color.DARK_GRAY);
    private final Font monoBold = FontFactory.getFont(FontFactory.COURIER_BOLD, 8, NAVY);

    /**
     * @param contentSha256 optional precursor content hash (printed alongside the sealed digest)
     * @param sealedNote    optional note about audit seal
     */
    public byte[] render(ForensicDossier dossier, String contentSha256, String sealedNote) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Document document = new Document(PageSize.A4, 48, 48, 64, 64);
            PdfWriter writer = PdfWriter.getInstance(document, out);
            writer.setCompressionLevel(0); // keep footer seal marker as literal ASCII for in-place stamp
            writer.setPageEvent(new HeaderFooterEvent(dossier, contentSha256, sealedNote));
            document.open();

            writeCover(document, dossier);
            document.newPage();
            writeCaseHeader(document, dossier);
            writeIdentity(document, dossier);
            writeRiskTimeline(document, dossier);
            writeEvidence(document, dossier);
            writeInterventionLog(document, dossier);
            writeAnalystActions(document, dossier);
            writeChallenges(document, dossier);
            writeAuditChain(document, dossier);
            writeMethodology(document, dossier);
            writeNoAudio(document, dossier);

            document.close();
            byte[] pdf = out.toByteArray();
            // Backup seal site if content-stream marker is missing (compression / encoding).
            if (indexOf(pdf, SHA_MARKER.getBytes(StandardCharsets.US_ASCII)) < 0) {
                byte[] trailer = ("\n%" + SHA_MARKER + SHA_PLACEHOLDER + "\n")
                        .getBytes(StandardCharsets.US_ASCII);
                byte[] combined = Arrays.copyOf(pdf, pdf.length + trailer.length);
                System.arraycopy(trailer, 0, combined, pdf.length, trailer.length);
                pdf = combined;
            }
            return stampDocumentSha256(pdf);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to render forensic dossier PDF: " + ex.getMessage(), ex);
        }
    }

    /**
     * Locate first {@link #SHA_MARKER}+window, compute exclusion digest, write hex into that window.
     * Only one seal window must exist in the PDF (cover-page footer).
     */
    static byte[] stampDocumentSha256(byte[] pdf) {
        byte[] marker = SHA_MARKER.getBytes(StandardCharsets.US_ASCII);
        int idx = indexOf(pdf, marker);
        if (idx < 0) {
            return pdf;
        }
        int windowStart = idx + marker.length;
        if (windowStart + SHA_WINDOW > pdf.length) {
            return pdf;
        }
        String digest = ForensicDossierService.documentSha256(pdf);
        byte[] hex = digest.getBytes(StandardCharsets.US_ASCII);
        if (hex.length != SHA_WINDOW) {
            throw new IllegalStateException("SHA-256 hex must be 64 ASCII chars");
        }
        byte[] stamped = Arrays.copyOf(pdf, pdf.length);
        System.arraycopy(hex, 0, stamped, windowStart, SHA_WINDOW);
        return stamped;
    }

    static int indexOf(byte[] haystack, byte[] needle) {
        return indexOfFrom(haystack, needle, 0);
    }

    static int indexOfFrom(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private void writeCover(Document document, ForensicDossier dossier) throws DocumentException {
        Paragraph brand = new Paragraph("SENTINELVOICE", titleFont);
        brand.setAlignment(Element.ALIGN_CENTER);
        brand.setSpacingBefore(80);
        document.add(brand);

        Paragraph sub = new Paragraph("FORENSIC EVIDENCE DOSSIER", headingFont);
        sub.setAlignment(Element.ALIGN_CENTER);
        sub.setSpacingBefore(12);
        document.add(sub);

        Paragraph ban = new Paragraph(
                "CONFIDENTIAL — BANK FRAUD INVESTIGATION FILE",
                FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, ACCENT)
        );
        ban.setAlignment(Element.ALIGN_CENTER);
        ban.setSpacingBefore(8);
        document.add(ban);

        document.add(Chunk.NEWLINE);
        document.add(kv("Session ID", dossier.sessionId()));
        document.add(kv("Generated at (UTC)", formatTs(dossier.generatedAtEpochMs())));
        document.add(kv("Generated by", nullToDash(dossier.generatedBy())));
        document.add(kv("Findings status", dossier.noFindings() ? "NO FINDINGS" : "FINDINGS PRESENT"));
        document.add(kv("Channel profile", dossier.caseHeader() == null ? "—" : dossier.caseHeader().channelProfile()));
        document.add(kv("Actuation adapter", dossier.caseHeader() == null ? "—" : dossier.caseHeader().adapterUsed()));

        Paragraph summary = new Paragraph(dossier.summary() == null ? "" : dossier.summary(), bodyFont);
        summary.setSpacingBefore(24);
        summary.setAlignment(Element.ALIGN_JUSTIFIED);
        document.add(summary);

        Paragraph legal = new Paragraph(
                "This package is assembled exclusively from Decision Plane retained state "
                        + "(scores, enums, reason codes, and hash-chained audit blocks). "
                        + "It does not retain call media or speech text. See the privacy statement "
                        + "and Methodology Appendix. "
                        + "The SHA-256 printed in each page footer is the audit-anchored digest of this file "
                        + "(window-exclusion seal) and matches DOSSIER_GENERATED.pdfSha256.",
                smallFont
        );
        legal.setSpacingBefore(36);
        document.add(legal);
    }

    private void writeCaseHeader(Document document, ForensicDossier dossier) throws DocumentException {
        section(document, "1. Case header");
        ForensicDossier.CaseHeader h = dossier.caseHeader();
        if (h == null) {
            document.add(new Paragraph("No case metadata retained.", bodyFont));
            return;
        }
        PdfPTable table = grid(2);
        addCells(table, "Session ID", h.sessionId());
        addCells(table, "Caller / Callee", h.callerId() + " → " + h.calleeId());
        addCells(table, "Start (UTC)", formatTs(h.startEpochMs()));
        addCells(table, "End (UTC)", formatTs(h.endEpochMs()));
        addCells(table, "Duration", h.durationMs() + " ms");
        addCells(table, "Channel profile", nullToDash(h.channelProfile()));
        addCells(table, "Adapter", nullToDash(h.adapterUsed()));
        addCells(table, "Scenario", nullToDash(h.scenarioId()));
        addCells(table, "Final level", nullToDash(h.finalInterventionLevel()));
        addCells(table, "Final smoothed risk", String.format("%.4f", h.finalSmoothedRisk()));
        document.add(table);
    }

    private void writeIdentity(Document document, ForensicDossier dossier) throws DocumentException {
        section(document, "2. Identity (§8.2)");
        if (dossier.identity() == null || dossier.identity().isEmpty()) {
            document.add(new Paragraph("No identity block retained for this session.", bodyFont));
        } else {
            PdfPTable table = grid(2);
            dossier.identity().forEach((k, v) -> addCells(table, k, String.valueOf(v)));
            document.add(table);
        }
        Paragraph analysis = new Paragraph(
                "Mismatch analysis: " + nullToDash(dossier.identityMismatchAnalysis()),
                bodyFont
        );
        analysis.setSpacingBefore(8);
        document.add(analysis);
    }

    private void writeRiskTimeline(Document document, ForensicDossier dossier) throws Exception {
        section(document, "3. Risk timeline");
        List<ForensicDossier.RiskSample> samples = dossier.riskTimeline();
        if (samples.isEmpty()) {
            document.add(new Paragraph(
                    "No risk samples retained. Timeline chart omitted for this no-findings / empty session.",
                    bodyFont
            ));
            return;
        }
        byte[] png = renderRiskChartPng(dossier);
        Image img = Image.getInstance(png);
        img.scaleToFit(480, 220);
        img.setAlignment(Element.ALIGN_CENTER);
        document.add(img);
        Paragraph note = new Paragraph(
                "Smoothed risk vs call time. Vertical markers indicate intervention level changes.",
                smallFont
        );
        note.setAlignment(Element.ALIGN_CENTER);
        document.add(note);
    }

    private void writeEvidence(Document document, ForensicDossier dossier) throws DocumentException {
        section(document, "4. Evidence table");
        List<EvidenceItem> items = dossier.evidence();
        if (items.isEmpty()) {
            document.add(new Paragraph("No reason codes fired during this session.", bodyFont));
            return;
        }
        PdfPTable table = new PdfPTable(new float[]{2.2f, 1.1f, 1.2f, 1.6f, 1.6f, 2.8f});
        table.setWidthPercentage(100);
        headerRow(table, "Code", "Severity", "When (UTC)", "Measured", "Baseline", "Narrative");
        for (EvidenceItem item : items) {
            table.addCell(cell(item.reasonCode(), monoFont));
            table.addCell(cell(item.severity(), bodyFont));
            table.addCell(cell(formatTs(item.observedAtEpochMs()), smallFont));
            table.addCell(cell(nullToDash(item.measuredValue()), bodyFont));
            table.addCell(cell(nullToDash(item.humanBaseline()), bodyFont));
            table.addCell(cell(nullToDash(item.narrative()), smallFont));
        }
        document.add(table);
    }

    private void writeInterventionLog(Document document, ForensicDossier dossier) throws DocumentException {
        section(document, "5. Intervention log");
        if (dossier.interventionLog().isEmpty()) {
            document.add(new Paragraph("No intervention level changes recorded.", bodyFont));
            return;
        }
        PdfPTable table = new PdfPTable(new float[]{1.6f, 1.4f, 1.4f, 1.2f, 2.4f, 1.2f});
        table.setWidthPercentage(100);
        headerRow(table, "When (UTC)", "From", "To", "Trigger", "Actions", "Latency");
        for (ForensicDossier.InterventionEvent e : dossier.interventionLog()) {
            table.addCell(cell(formatTs(e.tsEpochMs()), smallFont));
            table.addCell(cell(nullToDash(e.fromLevel()), smallFont));
            table.addCell(cell(nullToDash(e.toLevel()), smallFont));
            table.addCell(cell(nullToDash(e.trigger()), smallFont));
            table.addCell(cell(String.join(", ", e.actionsFired()), smallFont));
            table.addCell(cell(
                    e.latencyFromSessionStartMs() == null ? "—" : e.latencyFromSessionStartMs() + " ms",
                    smallFont
            ));
        }
        document.add(table);
    }

    private void writeAnalystActions(Document document, ForensicDossier dossier) throws DocumentException {
        section(document, "6. Analyst actions");
        if (dossier.analystActions().isEmpty()) {
            document.add(new Paragraph("No analyst overrides recorded.", bodyFont));
            return;
        }
        PdfPTable table = new PdfPTable(new float[]{1.6f, 1.3f, 1.4f, 1.4f, 3.0f});
        table.setWidthPercentage(100);
        headerRow(table, "When (UTC)", "Analyst", "From", "To", "Reason");
        for (ForensicDossier.AnalystAction a : dossier.analystActions()) {
            table.addCell(cell(formatTs(a.tsEpochMs()), smallFont));
            table.addCell(cell(nullToDash(a.analystId()), bodyFont));
            table.addCell(cell(nullToDash(a.fromLevel()), smallFont));
            table.addCell(cell(nullToDash(a.toLevel()), smallFont));
            table.addCell(cell(nullToDash(a.reason()), bodyFont));
        }
        document.add(table);
    }

    private void writeChallenges(Document document, ForensicDossier dossier) throws DocumentException {
        section(document, "7. Challenge results");
        if (dossier.challengeResults().isEmpty()) {
            document.add(new Paragraph("No liveness / challenge events recorded.", bodyFont));
            return;
        }
        for (ForensicDossier.ChallengeEvent c : dossier.challengeResults()) {
            document.add(new Paragraph(
                    formatTs(c.tsEpochMs()) + "  " + c.eventType() + "  " + c.payload(),
                    monoFont
            ));
        }
    }

    private void writeAuditChain(Document document, ForensicDossier dossier) throws DocumentException {
        section(document, "8. Audit chain");
        ForensicDossier.AuditChainSection a = dossier.auditChain();
        if (a == null) {
            document.add(new Paragraph("Audit chain unavailable.", bodyFont));
            return;
        }
        document.add(kv("Block count", String.valueOf(a.blockCount())));
        document.add(new Paragraph("Genesis hash:", smallFont));
        document.add(new Paragraph(nullToDash(a.genesisHash()), monoBold));
        document.add(new Paragraph("Final hash:", smallFont));
        document.add(new Paragraph(nullToDash(a.finalHash()), monoBold));
        document.add(kv("Verification", a.verificationValid() ? "VALID" : "INVALID / EMPTY"));
        document.add(new Paragraph(nullToDash(a.verificationDetail()), bodyFont));

        document.add(new Paragraph("First blocks:", smallFont));
        document.add(blockTable(a.firstBlocks()));
        document.add(new Paragraph("Last blocks:", smallFont));
        document.add(blockTable(a.lastBlocks()));
    }

    private void writeMethodology(Document document, ForensicDossier dossier) throws DocumentException {
        section(document, "9. Methodology appendix (admissibility)");
        Paragraph notice = new Paragraph(
                "Evidence without stated methodology and known error rates is not admissible-grade material. "
                        + "The following discloses which models produced which scores, their versions, "
                        + "and measured Equal Error Rate (EER) under the relevant channel profile. "
                        + "Acoustic detectors that appear strong in-domain often degrade sharply in the wild; "
                        + "SentinelVoice therefore requires multi-family corroboration before actuation.",
                bodyFont
        );
        notice.setSpacingAfter(8);
        document.add(notice);

        PdfPTable table = new PdfPTable(new float[]{1.4f, 2.0f, 1.0f, 1.4f, 1.0f, 2.2f});
        table.setWidthPercentage(100);
        headerRow(table, "Family", "Model / method", "Version", "Channel", "EER", "Notes");
        for (ForensicDossier.MethodologyEntry m : dossier.methodology()) {
            table.addCell(cell(m.evidenceFamily(), smallFont));
            table.addCell(cell(m.modelOrMethod(), smallFont));
            table.addCell(cell(m.version(), smallFont));
            table.addCell(cell(m.channelProfile(), smallFont));
            table.addCell(cell(m.measuredEer(), monoFont));
            table.addCell(cell(m.notes(), smallFont));
        }
        document.add(table);
    }

    private void writeNoAudio(Document document, ForensicDossier dossier) throws DocumentException {
        section(document, "10. Privacy statement (DPDP §8)");
        Paragraph p = new Paragraph(
                dossier.noAudioStatement() == null ? "" : dossier.noAudioStatement(),
                bodyFont
        );
        p.setAlignment(Element.ALIGN_JUSTIFIED);
        document.add(p);
    }

    private PdfPTable blockTable(List<ForensicDossier.AuditBlockSummary> blocks) throws DocumentException {
        PdfPTable table = new PdfPTable(new float[]{0.7f, 1.5f, 1.6f, 2.6f, 2.6f});
        table.setWidthPercentage(100);
        table.setSpacingBefore(4);
        table.setSpacingAfter(8);
        headerRow(table, "#", "When", "Type", "Current hash", "Previous hash");
        for (ForensicDossier.AuditBlockSummary b : blocks) {
            table.addCell(cell(String.valueOf(b.blockIndex()), smallFont));
            table.addCell(cell(formatTs(b.tsEpochMs()), smallFont));
            table.addCell(cell(b.eventType(), smallFont));
            table.addCell(cell(shortHash(b.currentHash()), monoFont));
            table.addCell(cell(shortHash(b.previousHash()), monoFont));
        }
        if (blocks.isEmpty()) {
            PdfPCell empty = cell("—", bodyFont);
            empty.setColspan(5);
            table.addCell(empty);
        }
        return table;
    }

    byte[] renderRiskChartPng(ForensicDossier dossier) throws Exception {
        XYSeries smoothed = new XYSeries("Smoothed risk");
        long origin = dossier.caseHeader() == null
                ? dossier.riskTimeline().getFirst().tsEpochMs()
                : dossier.caseHeader().startEpochMs();
        for (ForensicDossier.RiskSample s : dossier.riskTimeline()) {
            double tSec = Math.max(0, (s.tsEpochMs() - origin) / 1000.0);
            smoothed.add(tSec, s.smoothedRisk());
        }
        XYSeriesCollection dataset = new XYSeriesCollection(smoothed);
        JFreeChart chart = ChartFactory.createXYLineChart(
                "Smoothed risk vs time",
                "Call elapsed (s)",
                "Risk",
                dataset
        );
        chart.setBackgroundPaint(Color.WHITE);
        XYPlot plot = chart.getXYPlot();
        plot.setBackgroundPaint(new Color(0xF7, 0xF9, 0xFB));
        plot.setDomainGridlinePaint(RULE);
        plot.setRangeGridlinePaint(RULE);
        plot.getRangeAxis().setRange(0.0, 1.0);
        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer(true, false);
        renderer.setSeriesPaint(0, ACCENT);
        renderer.setSeriesStroke(0, new BasicStroke(2.0f));
        plot.setRenderer(renderer);

        XYSeries markers = new XYSeries("Level change");
        for (ForensicDossier.LevelMarker m : dossier.levelMarkers()) {
            double tSec = Math.max(0, (m.tsEpochMs() - origin) / 1000.0);
            double y = dossier.riskTimeline().stream()
                    .min((a, b) -> Long.compare(Math.abs(a.tsEpochMs() - m.tsEpochMs()), Math.abs(b.tsEpochMs() - m.tsEpochMs())))
                    .map(ForensicDossier.RiskSample::smoothedRisk)
                    .orElse(0.5);
            markers.add(tSec, y);
        }
        if (!markers.isEmpty()) {
            dataset.addSeries(markers);
            renderer.setSeriesPaint(1, new Color(0xB0, 0x3A, 0x2E));
            renderer.setSeriesShapesVisible(1, true);
            renderer.setSeriesLinesVisible(1, false);
            renderer.setSeriesShape(1, new java.awt.geom.Ellipse2D.Double(-3, -3, 6, 6));
        }

        BufferedImage image = chart.createBufferedImage(900, 360);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        return png.toByteArray();
    }

    private void section(Document document, String title) throws DocumentException {
        Paragraph p = new Paragraph(title, headingFont);
        p.setSpacingBefore(14);
        p.setSpacingAfter(6);
        document.add(p);
        PdfPTable rule = new PdfPTable(1);
        rule.setWidthPercentage(100);
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColor(RULE);
        cell.setFixedHeight(2f);
        rule.addCell(cell);
        document.add(rule);
    }

    private Paragraph kv(String key, String value) {
        Phrase phrase = new Phrase();
        phrase.add(new Chunk(key + ": ", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, NAVY)));
        phrase.add(new Chunk(nullToDash(value), bodyFont));
        Paragraph p = new Paragraph(phrase);
        p.setSpacingAfter(2);
        return p;
    }

    private PdfPTable grid(int cols) throws DocumentException {
        PdfPTable table = new PdfPTable(cols * 2);
        table.setWidthPercentage(100);
        table.setSpacingBefore(4);
        return table;
    }

    private void addCells(PdfPTable table, String key, String value) {
        table.addCell(cell(key, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, NAVY)));
        table.addCell(cell(nullToDash(value), bodyFont));
    }

    private void headerRow(PdfPTable table, String... headers) {
        for (String h : headers) {
            PdfPCell cell = cell(h, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Color.WHITE));
            cell.setBackgroundColor(NAVY);
            table.addCell(cell);
        }
    }

    private PdfPCell cell(String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text == null ? "—" : text, font));
        cell.setPadding(4);
        cell.setBorderColor(RULE);
        return cell;
    }

    private static String formatTs(long epochMs) {
        if (epochMs <= 0) {
            return "—";
        }
        return ISO.format(Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC));
    }

    private static String nullToDash(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    private static String shortHash(String hash) {
        if (hash == null || hash.isBlank()) {
            return "—";
        }
        return hash.length() <= 20 ? hash : hash.substring(0, 12) + "…" + hash.substring(hash.length() - 8);
    }

    private final class HeaderFooterEvent extends PdfPageEventHelper {
        private final ForensicDossier dossier;
        private final String contentSha256;
        private final String sealedNote;

        private HeaderFooterEvent(ForensicDossier dossier, String contentSha256, String sealedNote) {
            this.dossier = dossier;
            this.contentSha256 = contentSha256;
            this.sealedNote = sealedNote;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            Rectangle page = document.getPageSize();
            // —— Per-page header ——
            float headerY = page.getTop() - 28;
            ColumnTextShow(writer, "SENTINELVOICE · Forensic Evidence Dossier",
                    page.getLeft() + 48, headerY, Element.ALIGN_LEFT);
            ColumnTextShow(writer, "CONFIDENTIAL",
                    page.getRight() - 48, headerY, Element.ALIGN_RIGHT);
            try {
                com.lowagie.text.pdf.PdfContentByte cb = writer.getDirectContent();
                cb.setColorStroke(RULE);
                cb.setLineWidth(0.6f);
                cb.moveTo(page.getLeft() + 48, page.getTop() - 36);
                cb.lineTo(page.getRight() - 48, page.getTop() - 36);
                cb.stroke();
            } catch (Exception ignored) {
                // header rule best-effort
            }

            // —— Per-page footer ——
            float y = page.getBottom() + 36;
            String left = "Session " + dossier.sessionId();
            String center = "Page " + writer.getPageNumber();
            String right = formatTs(dossier.generatedAtEpochMs()) + " · " + nullToDash(dossier.generatedBy());
            ColumnTextShow(writer, left, page.getLeft() + 48, y, Element.ALIGN_LEFT);
            ColumnTextShow(writer, center, (page.getLeft() + page.getRight()) / 2, y, Element.ALIGN_CENTER);
            ColumnTextShow(writer, right, page.getRight() - 48, y, Element.ALIGN_RIGHT);

            // Seal window only on page 1 so a single exclusion digest is well-defined.
            StringBuilder hashLine = new StringBuilder();
            hashLine.append("Manifest: ").append(nullToDash(dossier.manifestSha256()));
            if (contentSha256 != null && !contentSha256.isBlank()) {
                hashLine.append("  |  Content: ").append(contentSha256);
            }
            if (writer.getPageNumber() == 1) {
                hashLine.append("  |  PDF-SHA256: ").append(SHA_MARKER).append(SHA_PLACEHOLDER);
            } else {
                hashLine.append("  |  PDF-SHA256: (see page 1 footer — matches audit pdfSha256)");
            }
            if (sealedNote != null && !sealedNote.isBlank()) {
                hashLine.append("  |  ").append(sealedNote);
            }
            try {
                com.lowagie.text.pdf.ColumnText.showTextAligned(
                        writer.getDirectContent(),
                        Element.ALIGN_LEFT,
                        new Phrase(hashLine.toString(), monoFont),
                        page.getLeft() + 48,
                        page.getBottom() + 18,
                        0
                );
            } catch (Exception ignored) {
                // footer best-effort
            }
        }

        private void ColumnTextShow(PdfWriter writer, String text, float x, float y, int align) {
            com.lowagie.text.pdf.ColumnText.showTextAligned(
                    writer.getDirectContent(),
                    align,
                    new Phrase(text, smallFont),
                    x,
                    y,
                    0
            );
        }
    }
}
