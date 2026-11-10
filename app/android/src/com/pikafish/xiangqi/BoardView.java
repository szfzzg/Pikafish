package com.pikafish.xiangqi;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.List;

/**
 * 自绘中国象棋棋盘。
 */
public class BoardView extends View {

    public interface Listener {
        void onSquareTap(int r, int c);
    }

    private static final String FILES = "abcdefghi";

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private Board board = new Board();
    private Listener listener;
    private int selectedR = -1;
    private int selectedC = -1;
    private List<Board.Move> legalTargets;
    private Board.Move lastMove;

    private float cell = 1f;
    private float left = 0f;
    private float top = 0f;

    public BoardView(Context context) {
        super(context);
        init();
    }

    public BoardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setContentDescription("中国象棋棋盘");
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setState(Board board, int selectedR, int selectedC,
                         List<Board.Move> legalTargets, Board.Move lastMove) {
        this.board = board;
        this.selectedR = selectedR;
        this.selectedC = selectedC;
        this.legalTargets = legalTargets;
        this.lastMove = lastMove;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            width = 1080;
        }
        int height = Math.round(width * 10.3f / 9.3f);
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            height = MeasureSpec.getSize(heightMeasureSpec);
        }
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        cell = Math.min(w / 9.3f, h / 10.3f);
        left = (w - 8f * cell) / 2f;
        top = (h - 9f * cell) / 2f;
    }

    private float px(int c) {
        return left + c * cell;
    }

    private float py(int r) {
        return top + (9 - r) * cell;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(0xFFF3DFB6);
        drawBorder(canvas);
        drawLastMove(canvas);
        drawGrid(canvas);
        drawMarks(canvas);
        drawRiverAndLabels(canvas);
        drawPieces(canvas);
        drawCheck(canvas);
        drawSelection(canvas);
        drawLegalTargets(canvas);
    }

    private void drawBorder(Canvas canvas) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(0xFF7A4A23);
        paint.setStrokeWidth(cell * 0.045f);
        rect.set(px(0) - 0.24f * cell, py(9) - 0.24f * cell,
                px(8) + 0.24f * cell, py(0) + 0.24f * cell);
        canvas.drawRect(rect, paint);

        paint.setColor(0xFFB98A5C);
        paint.setStrokeWidth(cell * 0.018f);
        rect.set(px(0) - 0.15f * cell, py(9) - 0.15f * cell,
                px(8) + 0.15f * cell, py(0) + 0.15f * cell);
        canvas.drawRect(rect, paint);
    }

    private void drawLastMove(Canvas canvas) {
        if (lastMove == null) {
            return;
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x55F2C14E);
        float pad = cell * 0.10f;
        drawSquareHighlight(canvas, lastMove.fr, lastMove.fc, pad);
        drawSquareHighlight(canvas, lastMove.tr, lastMove.tc, pad);
    }

    private void drawSquareHighlight(Canvas canvas, int r, int c, float pad) {
        rect.set(px(c) - cell / 2f + pad, py(r) - cell / 2f + pad,
                px(c) + cell / 2f - pad, py(r) + cell / 2f - pad);
        canvas.drawRect(rect, paint);
    }

    private void drawGrid(Canvas canvas) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(0xFF8A5B34);
        paint.setStrokeWidth(Math.max(1f, cell * 0.016f));

        for (int r = 0; r < 10; r++) {
            canvas.drawLine(px(0), py(r), px(8), py(r), paint);
        }
        for (int c = 0; c < 9; c++) {
            if (c == 0 || c == 8) {
                canvas.drawLine(px(c), py(0), px(c), py(9), paint);
            } else {
                canvas.drawLine(px(c), py(0), px(c), py(4), paint);
                canvas.drawLine(px(c), py(5), px(c), py(9), paint);
            }
        }
        canvas.drawLine(px(3), py(0), px(5), py(2), paint);
        canvas.drawLine(px(5), py(0), px(3), py(2), paint);
        canvas.drawLine(px(3), py(9), px(5), py(7), paint);
        canvas.drawLine(px(5), py(9), px(3), py(7), paint);
    }

    private void drawMarks(Canvas canvas) {
        int[][] marks = {
                {2, 1}, {2, 7}, {7, 1}, {7, 7},
                {3, 0}, {3, 2}, {3, 4}, {3, 6}, {3, 8},
                {6, 0}, {6, 2}, {6, 4}, {6, 6}, {6, 8}
        };
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(0xFFA0714A);
        paint.setStrokeWidth(Math.max(1f, cell * 0.014f));
        for (int[] mark : marks) {
            int r = mark[0], c = mark[1];
            float x = px(c), y = py(r);
            for (int dx = -1; dx <= 1; dx += 2) {
                for (int dy = -1; dy <= 1; dy += 2) {
                    if ((dx < 0 && c == 0) || (dx > 0 && c == 8)) {
                        continue;
                    }
                    canvas.drawLine(x + dx * 0.10f * cell, y + dy * 0.10f * cell,
                            x + dx * 0.20f * cell, y + dy * 0.10f * cell, paint);
                    canvas.drawLine(x + dx * 0.10f * cell, y + dy * 0.10f * cell,
                            x + dx * 0.10f * cell, y + dy * 0.20f * cell, paint);
                }
            }
        }
    }

    private void drawRiverAndLabels(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF9A6A3A);
        paint.setTextSize(cell * 0.50f);
        paint.setTextAlign(Paint.Align.CENTER);
        float riverY = (py(4) + py(5)) / 2f - (paint.ascent() + paint.descent()) / 2f;
        canvas.drawText("楚  河", px(2), riverY, paint);
        canvas.drawText("汉  界", px(6), riverY, paint);

        paint.setColor(0xFFB98A5C);
        paint.setTextSize(cell * 0.20f);
        for (int c = 0; c < 9; c++) {
            canvas.drawText(String.valueOf(FILES.charAt(c)),
                    px(c), py(0) + cell * 0.36f, paint);
        }
        for (int r = 0; r < 10; r++) {
            canvas.drawText(String.valueOf(r), px(0) - cell * 0.34f, py(r) + cell * 0.07f, paint);
        }
    }

    private void drawPieces(Canvas canvas) {
        float radius = cell * 0.40f;
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                char piece = board.grid[r][c];
                if (piece == '.') {
                    continue;
                }
                float x = px(c), y = py(r);

                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFFD8C29A);
                canvas.drawCircle(x + cell * 0.03f, y + cell * 0.04f, radius, paint);

                paint.setColor(0xFFFDF3DC);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(x, y, radius, paint);

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(cell * 0.035f);
                paint.setColor(0xFF9A6A3A);
                canvas.drawCircle(x, y, radius, paint);

                paint.setStrokeWidth(cell * 0.018f);
                paint.setColor(0xFFC9A06B);
                canvas.drawCircle(x, y, radius * 0.84f, paint);

                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Character.isUpperCase(piece) ? 0xFFB52D1F : 0xFF1B1B1B);
                paint.setTextSize(cell * 0.52f);
                paint.setTextAlign(Paint.Align.CENTER);
                Paint.FontMetrics fm = paint.getFontMetrics();
                float textY = y - (fm.ascent + fm.descent) / 2f;
                canvas.drawText(String.valueOf(Board.pieceName(piece)), x, textY, paint);
            }
        }
    }

    private void drawCheck(Canvas canvas) {
        if (!board.isInCheck()) {
            return;
        }
        int[] king = board.findKing(board.side);
        if (king == null) {
            return;
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(cell * 0.05f);
        paint.setColor(0xFFE74C3C);
        canvas.drawCircle(px(king[1]), py(king[0]), cell * 0.48f, paint);
    }

    private void drawSelection(Canvas canvas) {
        if (selectedR < 0) {
            return;
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(cell * 0.06f);
        paint.setColor(0xFFE67E22);
        canvas.drawCircle(px(selectedC), py(selectedR), cell * 0.47f, paint);
    }

    private void drawLegalTargets(Canvas canvas) {
        if (legalTargets == null) {
            return;
        }
        for (Board.Move move : legalTargets) {
            float x = px(move.tc), y = py(move.tr);
            if (board.grid[move.tr][move.tc] == '.') {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFF2E8B57);
                canvas.drawCircle(x, y, cell * 0.11f, paint);
            } else {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(cell * 0.05f);
                paint.setColor(0xFF2E8B57);
                canvas.drawCircle(x, y, cell * 0.46f, paint);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN && cell > 0) {
            int c = Math.round((event.getX() - left) / cell);
            int r = 9 - Math.round((event.getY() - top) / cell);
            if (r >= 0 && r <= 9 && c >= 0 && c <= 8
                    && Math.abs(event.getX() - px(c)) < cell * 0.48f
                    && Math.abs(event.getY() - py(r)) < cell * 0.48f) {
                if (listener != null) {
                    listener.onSquareTap(r, c);
                }
                performClick();
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
