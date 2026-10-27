package com.alfaa.chessoverlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * Draws the best-move arrow over the detected board area inside the overlay.
 * Move given in UCI form (e2e4, e7e8q). Square coords computed from the
 * board rect passed in by the overlay service.
 */
public class ArrowOverlayView extends View {

    private String move;              // uci like "e2e4"
    private int boardX, boardY, boardSize;
    private boolean flipped = false;  // set true when black is at bottom

    private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ArrowOverlayView(Context c) { super(c); init(); }
    public ArrowOverlayView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        arrowPaint.setColor(0x9800E676);
        arrowPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        arrowPaint.setStrokeWidth(14f);
        arrowPaint.setStrokeCap(Paint.Cap.ROUND);
        headPaint.setColor(0xFF00E676);
        headPaint.setStyle(Paint.Style.FILL);
    }

    public void setMove(String uci) { move = uci; invalidate(); }
    public void clearMove() { move = null; invalidate(); }
    public void setBoardRect(int x, int y, int size) { boardX = x; boardY = y; boardSize = size; invalidate(); }
    public void setFlipped(boolean f) { flipped = f; invalidate(); }

    private float[] squareCenter(char file, char rank) {
        int f = file - 'a';          // 0..7
        int rk = '8' - rank;         // 0..7 from top when white at bottom
        int row = flipped ? (7 - rk) : rk;
        int col = flipped ? (7 - f) : f;
        float cx = boardX + (col + 0.5f) * (boardSize / 8f);
        float cy = boardY + (row + 0.5f) * (boardSize / 8f);
        return new float[]{cx, cy};
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (move == null || move.length() < 4 || boardSize <= 0) return;
        float[] from = squareCenter(move.charAt(0), move.charAt(1));
        float[] to = squareCenter(move.charAt(2), move.charAt(3));

        float dx = to[0] - from[0], dy = to[1] - from[1];
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1) return;
        float ux = dx / len, uy = dy / len;
        // shorten at both ends
        float pad = boardSize / 16f;
        float sx = from[0] + ux * pad, sy = from[1] + uy * pad;
        float ex = to[0] - ux * pad, ey = to[1] - uy * pad;

        // shaft
        canvas.drawLine(sx, sy, ex - ux * boardSize / 24f, ey - uy * boardSize / 24f, arrowPaint);
        // head
        float hw = boardSize / 18f, hl = boardSize / 14f;
        Path head = new Path();
        head.moveTo(ex, ey);
        head.lineTo(ex - ux * hl - uy * hw, ey - uy * hl + ux * hw);
        head.lineTo(ex - ux * hl + uy * hw, ey - uy * hl - ux * hw);
        head.close();
        canvas.drawPath(head, headPaint);
    }
}
