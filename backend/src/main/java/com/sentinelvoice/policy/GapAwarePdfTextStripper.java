package com.sentinelvoice.policy;

import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.List;

/**
 * PDF text stripper that inserts wide gaps between words when the X distance is large,
 * so table columns become detectable as ≥3 cells separated by 2+ spaces.
 */
final class GapAwarePdfTextStripper extends PDFTextStripper {

    private static final float GAP_FACTOR = 2.2f;

    GapAwarePdfTextStripper() throws IOException {
        setSortByPosition(true);
    }

    @Override
    protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
        if (textPositions == null || textPositions.isEmpty()) {
            super.writeString(text, textPositions);
            return;
        }
        StringBuilder sb = new StringBuilder();
        TextPosition prev = null;
        for (TextPosition pos : textPositions) {
            if (prev != null) {
                float gap = pos.getXDirAdj() - (prev.getXDirAdj() + prev.getWidthDirAdj());
                float spaceWidth = Math.max(prev.getWidthOfSpace(), prev.getWidthDirAdj() * 0.5f);
                if (gap > spaceWidth * GAP_FACTOR) {
                    // Wide gap → emit two spaces so column split sees ≥3 cells
                    sb.append("  ");
                } else if (gap > spaceWidth * 0.35f) {
                    sb.append(' ');
                }
            }
            sb.append(pos.getUnicode());
            prev = pos;
        }
        writeString(sb.toString());
    }
}
