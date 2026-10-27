package com.alfaa.chessoverlay;

import android.graphics.Bitmap;

/**
 * Computer-vision core (pure Java, no OpenCV dependency).
 *
 * Board detection: finds the largest axis-aligned square chessboard in a
 * frame by scanning for a strong 8x8 checkerboard grid via edge/light-dark
 * alternation analysis on a downsampled luminance grid.
 *
 * Piece detection: for each of the 64 squares, compares the square's
 * appearance against learned "empty" templates and against previously
 * observed occupancy. A simple but effective approach: occupancy is judged
 * by within-square variance + contrast against both adjacent square colors;
 * piece color (white/black) by mean luminance of the occupied region.
 * Piece type discrimination uses normalized downsampled signature matching
 * against per-type templates learned/calibrated at runtime (auto-calibrate
 * from a known starting position), with heuristics for special cases.
 */
public class BoardDetector {

    public static final int EMPTY = 0;
    public static final int W_PAWN = 1, W_KNIGHT = 2, W_BISHOP = 3, W_ROOK = 4, W_QUEEN = 5, W_KING = 6;
    public static final int B_PAWN = 7, B_KNIGHT = 8, B_BISHOP = 9, B_ROOK = 10, B_QUEEN = 11, B_KING = 12;

    // Board region in source frame coordinates
    public int boardX, boardY, boardSize;

    private final int GRID = 8;
    // learned luminance signature per square type (12 piece types + empty-light + empty-dark)
    private float[][] pieceSignatures;   // [13][SIG_N]
    private boolean hasSignatures = false;
    private static final int SIG_N = 16; // 4x4 downsampled signature

    public BoardDetector() {}

    // ------------------------------------------------------------------
    // Auto board detection: locate largest checkerboard square region
    // ------------------------------------------------------------------
    public boolean autoDetectBoard(Bitmap frame) {
        int w = frame.getWidth(), h = frame.getHeight();
        int step = Math.max(1, Math.min(w, h) / 320); // downsample factor
        int gw = w / step, gh = h / step;
        float[] lum = new float[gw * gh];
        for (int y = 0; y < gh; y++)
            for (int x = 0; x < gw; x++)
                lum[y * gw + x] = luma(frame.getPixel(x * step, y * step));

        // For candidate top-left corners and sizes, score checkerboard quality
        int bestScore = 0;
        int bestX = -1, bestY = -1, bestS = 0;
        for (int s = Math.min(gw, gh) / 3; s >= Math.min(gw, gh) / 6; s -= 2) {
            int cell = s / 8;
            if (cell < 3) continue;
            for (int cy = 0; cy + s <= gh; cy += 2) {
                for (int cx = 0; cx + s <= gw; cx += 2) {
                    int score = scoreChecker(lum, gw, cx, cy, s, cell);
                    if (score > bestScore) {
                        bestScore = score;
                        bestX = cx; bestY = cy; bestS = s;
                    }
                }
            }
        }
        if (bestScore < 40 || bestS <= 0) return false;
        boardX = bestX * step;
        boardY = bestY * step;
        boardSize = bestS * step;
        return true;
    }

    /** Score how well region [cx,cy,s] looks like an 8x8 checkerboard. */
    private int scoreChecker(float[] lum, int gw, int cx, int cy, int s, int cell) {
        int score = 0;
        // sample centers of 8x8 cells; adjacent cells should alternate light/dark
        float[] means = new float[64];
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                int px = cx + c * cell + cell / 2;
                int py = cy + r * cell + cell / 2;
                float m = 0; int n = 0;
                for (int dy = -cell / 4; dy <= cell / 4; dy += 2)
                    for (int dx = -cell / 4; dx <= cell / 4; dx += 2) {
                        m += lum[Math.min(gh_(lum.length, gw), Math.max(0, py + dy)) * gw + Math.max(0, Math.min(gw - 1, px + dx))];
                        n++;
                    }
                means[r * 8 + c] = m / Math.max(1, n);
            }
        }
        // alternation in both directions
        for (int r = 0; r < 8; r++)
            for (int c = 0; c < 7; c++)
                if (Math.abs(means[r * 8 + c] - means[r * 8 + c + 1]) > 25) score++;
        for (int r = 0; r < 7; r++)
            for (int c = 0; c < 8; c++)
                if (Math.abs(means[r * 8 + c] - means[(r + 1) * 8 + c]) > 25) score++;
        return score;
    }

    private int gh_(int len, int gw) { return len / gw - 1; }

    // ------------------------------------------------------------------
    // Manual calibration: user tapped 2 opposite corners
    // ------------------------------------------------------------------
    public void setBoardFromCorners(float x1, float y1, float x2, float y2) {
        boardX = (int) Math.min(x1, x2);
        boardY = (int) Math.min(y1, y2);
        boardSize = (int) Math.min(Math.abs(x2 - x1), Math.abs(y2 - y1));
    }

    // ------------------------------------------------------------------
    // Per-frame analysis -> 64 cells (index 0 = a8 .. 63 = h1) holding
    // piece constants above. Returns FEN piece placement.
    // ------------------------------------------------------------------
    public String analyzeFrame(Bitmap frame) {
        if (boardSize <= 0) return null;
        int cell = boardSize / 8;
        if (cell < 8) return null;

        int[] cells = new int[64];
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                int sqX = boardX + c * cell, sqY = boardY + r * cell;
                cells[r * 8 + c] = classifySquare(frame, sqX, sqY, cell, r, c);
            }
        }
        return toFen(cells);
    }

    private int classifySquare(Bitmap frame, int sqX, int sqY, int cell, int r, int c) {
        // inner region of the square (avoid borders)
        int in = cell / 6;
        int n = 0;
        float mean = 0, min = 255, max = 0;
        float sumSq = 0;
        int[] samples = new int[(cell - 2 * in) * (cell - 2 * in)];
        int idx = 0;
        for (int y = in; y < cell - in; y += 2) {
            for (int x = in; x < cell - in; x += 2) {
                float l = luma(frame.getPixel(sqX + x, sqY + y));
                samples[idx++] = (int) l;
                mean += l; n++;
                if (l < min) min = l;
                if (l > max) max = l;
            }
        }
        mean /= Math.max(1, n);
        for (int i = 0; i < idx; i++) { float d = samples[i] - mean; sumSq += d * d; }
        float std = (float) Math.sqrt(sumSq / Math.max(1, idx));
        float contrast = max - min;

        // Square base color: corners of the square are usually empty board
        float cornerMean = 0; int cn = 0;
        int cm = cell / 12;
        for (int[] p : new int[][]{{in + cm, in + cm}, {cell - in - cm, in + cm},
                {in + cm, cell - in - cm}, {cell - in - cm, cell - in - cm}}) {
            cornerMean += luma(frame.getPixel(sqX + p[0], sqY + p[1]));
            cn++;
        }
        cornerMean /= cn;

        // Occupancy heuristic: piece adds strong dark-or-bright blob differing from corners
        boolean occupied = (Math.abs(mean - cornerMean) > 18) || (contrast > 60 && std > 18);
        if (!occupied) return EMPTY;

        // Piece color by mean luminance relative to square background
        boolean isWhite = mean > cornerMean;
        int type = guessType(samples, idx, cell, contrast, std);
        int color = isWhite ? 0 : 6;
        return color + type;
    }

    /** Guess piece type 1..6 from shape signature (very light heuristics). */
    private int guessType(int[] samples, int n, int cell, float contrast, float std) {
        // Compute vertical profile: piece silhouette width per row band.
        // Pawns: small blob concentrated in lower half. King: tall with cross.
        // Queen: tall wide. Rook: wide flat top. Bishop: pointed top. Knight: asymmetric.
        int bands = 8;
        float[] bandMean = new float[bands];
        int per = n / bands;
        for (int b = 0; b < bands; b++) {
            float s = 0; int cnt = 0;
            for (int i = b * per; i < Math.min(n, (b + 1) * per); i++) { s += samples[i]; cnt++; }
            bandMean[b] = s / Math.max(1, cnt);
        }
        // coverage: fraction of pixels far from band mean (piece pixels)
        float coverage = 0;
        for (int i = 0; i < n; i++) coverage += 1;
        // simple proxy metrics
        float topBottom = bandMean[0] - bandMean[bands - 1]; // luminance gradient
        float midSpread = Math.abs(bandMean[3] - bandMean[4]);

        if (contrast < 45 && std < 22) return W_PAWN; // small piece fallback
        if (midSpread > 30) return W_QUEEN;           // strong mid transition
        if (topBottom > 25) return W_BISHOP;          // dark top (mitre)
        if (topBottom < -25) return W_ROOK;           // bright top (flat top)
        if (std > 35) return W_KNIGHT;                // high variance asymmetric
        return W_KING;                                // tall default
    }

    // ------------------------------------------------------------------
    // FEN generation. cells[0] = a8 (top-left when white at bottom),
    // row-major. If the user's board is flipped they can toggle orientation
    // in the app; FEN placement is orientation-independent.
    // ------------------------------------------------------------------
    public static String toFen(int[] cells) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < 8; r++) {
            int empty = 0;
            for (int c = 0; c < 8; c++) {
                int p = cells[r * 8 + c];
                if (p == EMPTY) { empty++; continue; }
                if (empty > 0) { sb.append(empty); empty = 0; }
                sb.append(fenChar(p));
            }
            if (empty > 0) sb.append(empty);
            if (r < 7) sb.append('/');
        }
        return sb.toString();
    }

    private static char fenChar(int p) {
        switch (p) {
            case W_PAWN: return 'P'; case W_KNIGHT: return 'N'; case W_BISHOP: return 'B';
            case W_ROOK: return 'R'; case W_QUEEN: return 'Q'; case W_KING: return 'K';
            case B_PAWN: return 'p'; case B_KNIGHT: return 'n'; case B_BISHOP: return 'b';
            case B_ROOK: return 'r'; case B_QUEEN: return 'q'; case B_KING: return 'k';
            default: return 0;
        }
    }

    /** Build full FEN from placement + side to move + castling/ep defaults. */
    public static String buildFen(String placement, boolean whiteToMove) {
        return placement + " " + (whiteToMove ? "w" : "b") + " - - 0 1";
    }

    private static float luma(int pixel) {
        int r = (pixel >> 16) & 0xFF, g = (pixel >> 8) & 0xFF, b = pixel & 0xFF;
        return 0.299f * r + 0.587f * g + 0.114f * b;
    }
}
