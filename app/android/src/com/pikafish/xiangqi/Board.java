package com.pikafish.xiangqi;

import java.util.ArrayList;
import java.util.List;

/**
 * 中国象棋棋盘与规则实现。
 * grid[0] 为红方底线（rank 0），grid[9] 为黑方底线。
 */
public final class Board {

    public static final char RED = 'r';
    public static final char BLACK = 'b';
    public static final String START_FEN =
            "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1";
    private static final String FILES = "abcdefghi";

    private static final int[][] ORTHO = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAG = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final int[][] HORSE = {
            {2, 1}, {2, -1}, {-2, 1}, {-2, -1},
            {1, 2}, {1, -2}, {-1, 2}, {-1, -2}
    };

    public char[][] grid = new char[10][9];
    public char side = RED;
    public int halfmove = 0;
    public int fullmove = 1;

    public Board() {
        loadFen(START_FEN);
    }

    public static char colorOf(char piece) {
        return Character.isUpperCase(piece) ? RED : BLACK;
    }

    public static char pieceName(char piece) {
        switch (piece) {
            case 'K': return '帅';
            case 'A': return '仕';
            case 'B': return '相';
            case 'N': return '马';
            case 'R': return '车';
            case 'C': return '炮';
            case 'P': return '兵';
            case 'k': return '将';
            case 'a': return '士';
            case 'b': return '象';
            case 'n': return '马';
            case 'r': return '车';
            case 'c': return '炮';
            case 'p': return '卒';
            default: return piece;
        }
    }

    public void loadFen(String fen) {
        String[] fields = fen.trim().split("\\s+");
        String[] rows = fields[0].split("/");
        if (rows.length != 10) {
            throw new IllegalArgumentException("FEN 行数错误");
        }
        char[][] g = new char[10][9];
        for (int i = 0; i < 10; i++) {
            int rank = 9 - i;
            int col = 0;
            for (char ch : rows[i].toCharArray()) {
                if (Character.isDigit(ch)) {
                    int count = ch - '0';
                    for (int n = 0; n < count; n++) {
                        g[rank][col++] = '.';
                    }
                } else {
                    g[rank][col++] = ch;
                }
            }
            if (col != 9) {
                throw new IllegalArgumentException("FEN 列数错误");
            }
        }
        grid = g;
        side = "w".equals(fields[1]) ? RED : BLACK;
        if (fields.length >= 6) {
            halfmove = Integer.parseInt(fields[4]);
            fullmove = Integer.parseInt(fields[5]);
        } else {
            halfmove = 0;
            fullmove = 1;
        }
    }

    public String fen() {
        StringBuilder sb = new StringBuilder();
        for (int r = 9; r >= 0; r--) {
            int empty = 0;
            for (int c = 0; c < 9; c++) {
                char p = grid[r][c];
                if (p == '.') {
                    empty++;
                } else {
                    if (empty > 0) {
                        sb.append(empty);
                        empty = 0;
                    }
                    sb.append(p);
                }
            }
            if (empty > 0) {
                sb.append(empty);
            }
            if (r > 0) {
                sb.append('/');
            }
        }
        sb.append(side == RED ? " w - - " : " b - - ");
        sb.append(halfmove).append(' ').append(fullmove);
        return sb.toString();
    }

    public Board copy() {
        Board b = new Board();
        for (int r = 0; r < 10; r++) {
            System.arraycopy(grid[r], 0, b.grid[r], 0, 9);
        }
        b.side = side;
        b.halfmove = halfmove;
        b.fullmove = fullmove;
        return b;
    }

    public char applyMove(Move m) {
        char piece = grid[m.fr][m.fc];
        char captured = grid[m.tr][m.tc];
        if (piece == '.') {
            throw new IllegalArgumentException("起点没有棋子");
        }
        grid[m.tr][m.tc] = piece;
        grid[m.fr][m.fc] = '.';
        if (Character.toUpperCase(piece) == 'P' || captured != '.') {
            halfmove = 0;
        } else {
            halfmove++;
        }
        if (side == BLACK) {
            fullmove++;
        }
        side = side == RED ? BLACK : RED;
        return captured;
    }

    // ---------------------------------------------------------------------
    // 着法生成
    // ---------------------------------------------------------------------

    private boolean inPalace(char color, int r, int c) {
        if (c < 3 || c > 5) {
            return false;
        }
        return color == RED ? (r >= 0 && r <= 2) : (r >= 7 && r <= 9);
    }

    private void addIfPossible(List<Move> moves, int fr, int fc, int tr, int tc, char color) {
        if (tr < 0 || tr > 9 || tc < 0 || tc > 8) {
            return;
        }
        char target = grid[tr][tc];
        if (target == '.' || colorOf(target) != color) {
            moves.add(new Move(fr, fc, tr, tc));
        }
    }

    public List<Move> pseudoMovesFrom(int r, int c) {
        List<Move> moves = new ArrayList<>();
        char piece = grid[r][c];
        if (piece == '.') {
            return moves;
        }
        char color = colorOf(piece);
        char kind = Character.toUpperCase(piece);

        switch (kind) {
            case 'K':
                for (int[] d : ORTHO) {
                    int tr = r + d[0], tc = c + d[1];
                    if (inPalace(color, tr, tc)) {
                        addIfPossible(moves, r, c, tr, tc, color);
                    }
                }
                break;

            case 'A':
                for (int[] d : DIAG) {
                    int tr = r + d[0], tc = c + d[1];
                    if (inPalace(color, tr, tc)) {
                        addIfPossible(moves, r, c, tr, tc, color);
                    }
                }
                break;

            case 'B':
                for (int[] d : DIAG) {
                    int tr = r + 2 * d[0], tc = c + 2 * d[1];
                    if (tr < 0 || tr > 9 || tc < 0 || tc > 8) {
                        continue;
                    }
                    if (color == RED && tr > 4) {
                        continue;
                    }
                    if (color == BLACK && tr < 5) {
                        continue;
                    }
                    if (grid[r + d[0]][c + d[1]] != '.') {
                        continue;
                    }
                    addIfPossible(moves, r, c, tr, tc, color);
                }
                break;

            case 'N':
                for (int[] d : HORSE) {
                    int tr = r + d[0], tc = c + d[1];
                    if (tr < 0 || tr > 9 || tc < 0 || tc > 8) {
                        continue;
                    }
                    int lr, lc;
                    if (Math.abs(d[0]) == 2) {
                        lr = r + d[0] / 2;
                        lc = c;
                    } else {
                        lr = r;
                        lc = c + d[1] / 2;
                    }
                    if (grid[lr][lc] != '.') {
                        continue;
                    }
                    addIfPossible(moves, r, c, tr, tc, color);
                }
                break;

            case 'R':
                for (int[] d : ORTHO) {
                    int tr = r + d[0], tc = c + d[1];
                    while (tr >= 0 && tr <= 9 && tc >= 0 && tc <= 8) {
                        char target = grid[tr][tc];
                        if (target == '.') {
                            moves.add(new Move(r, c, tr, tc));
                        } else {
                            if (colorOf(target) != color) {
                                moves.add(new Move(r, c, tr, tc));
                            }
                            break;
                        }
                        tr += d[0];
                        tc += d[1];
                    }
                }
                break;

            case 'C':
                for (int[] d : ORTHO) {
                    int tr = r + d[0], tc = c + d[1];
                    while (tr >= 0 && tr <= 9 && tc >= 0 && tc <= 8 && grid[tr][tc] == '.') {
                        moves.add(new Move(r, c, tr, tc));
                        tr += d[0];
                        tc += d[1];
                    }
                    if (tr < 0 || tr > 9 || tc < 0 || tc > 8) {
                        continue;
                    }
                    tr += d[0];
                    tc += d[1];
                    while (tr >= 0 && tr <= 9 && tc >= 0 && tc <= 8) {
                        char target = grid[tr][tc];
                        if (target != '.') {
                            if (colorOf(target) != color) {
                                moves.add(new Move(r, c, tr, tc));
                            }
                            break;
                        }
                        tr += d[0];
                        tc += d[1];
                    }
                }
                break;

            case 'P':
                int forward = color == RED ? 1 : -1;
                addIfPossible(moves, r, c, r + forward, c, color);
                if ((color == RED && r >= 5) || (color == BLACK && r <= 4)) {
                    addIfPossible(moves, r, c, r, c - 1, color);
                    addIfPossible(moves, r, c, r, c + 1, color);
                }
                break;

            default:
                break;
        }
        return moves;
    }

    public int[] findKing(char color) {
        char king = color == RED ? 'K' : 'k';
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                if (grid[r][c] == king) {
                    return new int[]{r, c};
                }
            }
        }
        return null;
    }

    public boolean kingsFace() {
        int[] rk = findKing(RED);
        int[] bk = findKing(BLACK);
        if (rk == null || bk == null || rk[1] != bk[1]) {
            return false;
        }
        int c = rk[1];
        int low = Math.min(rk[0], bk[0]);
        int high = Math.max(rk[0], bk[0]);
        for (int r = low + 1; r < high; r++) {
            if (grid[r][c] != '.') {
                return false;
            }
        }
        return true;
    }

    public boolean isInCheck() {
        return isInCheck(side);
    }

    public boolean isInCheck(char color) {
        int[] king = findKing(color);
        if (king == null) {
            return true;
        }
        char enemy = color == RED ? BLACK : RED;
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                char p = grid[r][c];
                if (p == '.' || colorOf(p) != enemy) {
                    continue;
                }
                for (Move m : pseudoMovesFrom(r, c)) {
                    if (m.tr == king[0] && m.tc == king[1]) {
                        return true;
                    }
                }
            }
        }
        return kingsFace();
    }

    public List<Move> legalMoves() {
        return legalMoves(side);
    }

    public List<Move> legalMoves(char color) {
        List<Move> result = new ArrayList<>();
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                char p = grid[r][c];
                if (p == '.' || colorOf(p) != color) {
                    continue;
                }
                for (Move m : pseudoMovesFrom(r, c)) {
                    Board next = copy();
                    next.applyMove(m);
                    if (!next.isInCheck(color)) {
                        result.add(m);
                    }
                }
            }
        }
        return result;
    }

    public static final class Move {
        public final int fr, fc, tr, tc;

        public Move(int fr, int fc, int tr, int tc) {
            this.fr = fr;
            this.fc = fc;
            this.tr = tr;
            this.tc = tc;
        }

        public String uci() {
            return "" + FILES.charAt(fc) + fr + FILES.charAt(tc) + tr;
        }

        public static Move parse(String uci) {
            if (uci == null || uci.length() < 4) {
                throw new IllegalArgumentException("非法着法");
            }
            int fc = FILES.indexOf(uci.charAt(0));
            int fr = uci.charAt(1) - '0';
            int tc = FILES.indexOf(uci.charAt(2));
            int tr = uci.charAt(3) - '0';
            if (fc < 0 || tc < 0 || fr < 0 || fr > 9 || tr < 0 || tr > 9) {
                throw new IllegalArgumentException("非法着法");
            }
            return new Move(fr, fc, tr, tc);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Move)) {
                return false;
            }
            Move m = (Move) o;
            return fr == m.fr && fc == m.fc && tr == m.tr && tc == m.tc;
        }

        @Override
        public int hashCode() {
            return ((fr * 9 + fc) * 10 + tr) * 9 + tc;
        }

        @Override
        public String toString() {
            return uci();
        }
    }
}
