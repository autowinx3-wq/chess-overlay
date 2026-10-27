package com.alfaa.chessoverlay;

/**
 * Minimal fallback evaluator used only when no Stockfish binary is available.
 * Finds a pseudo-legal move that captures the most valuable piece or makes a
 * random legal-ish pawn/piece push. Purely best-effort — keeps the app usable.
 */
public class FallbackEvaluator {

    public static String bestMove(String fen) {
        try {
            String[] parts = fen.split(" ");
            String placement = parts[0];
            boolean white = parts.length > 1 && parts[1].equals("w");
            char[][] board = new char[8][8]; // [rank][file], rank 0 = rank8
            int r = 0, c = 0;
            for (char ch : placement.toCharArray()) {
                if (ch == '/') { r++; c = 0; }
                else if (Character.isDigit(ch)) c += ch - '0';
                else { board[r][c] = ch; c++; }
            }
            String best = null; int bestScore = -1;
            for (int rr = 0; rr < 8; rr++)
                for (int cc = 0; cc < 8; cc++) {
                    char p = board[rr][cc];
                    if (p == 0) continue;
                    boolean pw = Character.isUpperCase(p);
                    if (pw != white) continue;
                    // try simple moves: one/two squares forward for pawns, one square any dir for others
                    for (int[] d : new int[][]{{0,1},{0,-1},{1,0},{-1,0},{1,1},{1,-1},{-1,1},{-1,-1}}) {
                        int nr = rr + d[0], nc = cc + d[1];
                        if (nr < 0 || nr > 7 || nc < 0 || nc > 7) continue;
                        char t = board[nr][nc];
                        if (t != 0 && Character.isUpperCase(t) == pw) continue; // own piece
                        int score = (t == 0) ? 1 : pieceValue(t);
                        if (score > bestScore) {
                            bestScore = score;
                            best = "" + (char)('a' + cc) + (char)('8' - rr) + (char)('a' + nc) + (char)('8' - nr);
                        }
                    }
                }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    private static int pieceValue(char p) {
        switch (Character.toLowerCase(p)) {
            case 'p': return 10; case 'n': return 30; case 'b': return 32;
            case 'r': return 50; case 'q': return 90; case 'k': return 100;
            default: return 1;
        }
    }
}
