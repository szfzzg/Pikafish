package com.pikafish.xiangqi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pikafish 象棋 Android 主界面：人机对弈、局面分析、着法记录与悔棋。
 */
public class MainActivity extends Activity implements BoardView.Listener, UciEngine.Listener {

    private static final char HUMAN = Board.RED;
    private static final String[] DIFFICULTY_NAMES = {
            "快速（0.5 秒）", "普通（1.5 秒）", "较强（3 秒）", "最强（5 秒）"
    };
    private static final int[] DIFFICULTY_MS = {500, 1500, 3000, 5000};

    private Board board = new Board();
    private final List<HistoryEntry> history = new ArrayList<>();
    private final List<Board.Move> legalForSelected = new ArrayList<>();
    private Board.Move lastMove;
    private int selectedR = -1;
    private int selectedC = -1;
    private boolean busy = false;
    private boolean gameOver = false;
    private boolean engineReady = false;
    private boolean engineFailed = false;
    private String resultText = "";

    private BoardView boardView;
    private TextView statusView;
    private TextView movesView;
    private TextView analysisView;
    private Button newButton;
    private Button undoButton;
    private Button analyzeButton;
    private Spinner strengthSpinner;
    private UciEngine engine;

    private static class HistoryEntry {
        String uci;
        char piece;
        char captured;
        char side;
        String fenBefore;
        Board.Move move;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        updateBoard();
        updateMoveList();
        updateControls();
        setStatus("正在准备引擎…");
        prepareEngine();
    }

    @Override
    protected void onDestroy() {
        if (engine != null) {
            engine.close();
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------------
    // 界面
    // ---------------------------------------------------------------------

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFFECE3D2);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(10), dp(12), dp(16));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("Pikafish 象棋");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF5B3A1E);
        root.addView(title);

        boardView = new BoardView(this);
        boardView.setListener(this);
        LinearLayout.LayoutParams boardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        boardParams.topMargin = dp(8);
        root.addView(boardView, boardParams);

        statusView = new TextView(this);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        statusView.setTypeface(Typeface.DEFAULT_BOLD);
        statusView.setTextColor(0xFF7A4A23);
        statusView.setPadding(0, dp(10), 0, dp(8));
        root.addView(statusView);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        newButton = makeButton("新局");
        undoButton = makeButton("悔棋");
        analyzeButton = makeButton("分析局面");
        Button[] allButtons = {newButton, undoButton, analyzeButton};
        for (int i = 0; i < allButtons.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
            if (i > 0) {
                lp.leftMargin = dp(8);
            }
            buttons.addView(allButtons[i], lp);
        }
        root.addView(buttons);

        LinearLayout strengthRow = new LinearLayout(this);
        strengthRow.setOrientation(LinearLayout.HORIZONTAL);
        strengthRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams strengthParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        strengthParams.topMargin = dp(10);
        TextView strengthLabel = new TextView(this);
        strengthLabel.setText("思考时间：");
        strengthLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        strengthLabel.setTextColor(0xFF5B3A1E);
        strengthSpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, DIFFICULTY_NAMES);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        strengthSpinner.setAdapter(adapter);
        strengthSpinner.setSelection(1);
        strengthRow.addView(strengthLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        strengthRow.addView(strengthSpinner, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(strengthRow, strengthParams);

        newButton.setOnClickListener(v -> newGame());
        undoButton.setOnClickListener(v -> undo());
        analyzeButton.setOnClickListener(v -> startAnalysis());

        root.addView(sectionLabel("着法记录"));
        movesView = createInfoText();
        ScrollView movesScroll = new ScrollView(this);
        movesScroll.addView(movesView, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams movesParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(96));
        root.addView(movesScroll, movesParams);

        root.addView(sectionLabel("局面分析"));
        analysisView = createInfoText();
        ScrollView analysisScroll = new ScrollView(this);
        analysisScroll.addView(analysisView, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams analysisParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(140));
        root.addView(analysisScroll, analysisParams);

        setContentView(scroll);
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setTextColor(Color.WHITE);
        button.setBackgroundTintList(ColorStateList.valueOf(0xFF8B5E34));
        return button;
    }

    private TextView sectionLabel(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(0xFF5B3A1E);
        view.setPadding(0, dp(12), 0, dp(4));
        return view;
    }

    private TextView createInfoText() {
        TextView view = new TextView(this);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setTextColor(0xFF2B2B2B);
        view.setBackgroundColor(0xFFFFFDF7);
        view.setPadding(dp(8), dp(8), dp(8), dp(8));
        view.setLineSpacing(dp(2), 1f);
        view.setTextIsSelectable(true);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void setStatus(String text) {
        statusView.setText(text);
    }

    // ---------------------------------------------------------------------
    // 引擎初始化
    // ---------------------------------------------------------------------

    private void prepareEngine() {
        new Thread(() -> {
            try {
                File net = new File(getFilesDir(), "pikafish.nnue");
                File marker = new File(getFilesDir(), "net.ok");
                if (!net.exists() || !marker.exists() || net.length() < 10_000_000L) {
                    copyAsset("pikafish.nnue", net);
                    if (!marker.exists() && !marker.createNewFile()) {
                        throw new IOException("无法写入引擎标记文件");
                    }
                }
                String enginePath = resolveEnginePath();
                engine = new UciEngine();
                engine.start(enginePath, net.getAbsolutePath(), this);
            } catch (Exception e) {
                final String message = "初始化失败：" + (e.getMessage() == null ? e.toString() : e.getMessage());
                runOnUiThread(() -> onEngineError(message));
            }
        }, "engine-setup").start();
    }

    private void copyAsset(String assetName, File target) throws IOException {
        try (InputStream in = getAssets().open(assetName);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) > 0) {
                out.write(buffer, 0, count);
            }
        }
    }

    private String resolveEnginePath() throws IOException {
        File nativeEngine = new File(getApplicationInfo().nativeLibraryDir, "libpikafish.so");
        if (!nativeEngine.exists()) {
            throw new IOException("引擎文件不存在：" + nativeEngine);
        }
        if (nativeEngine.canExecute()) {
            return nativeEngine.getAbsolutePath();
        }
        // 个别设备不会给解压后的 native 库可执行权限，退回到应用私有目录。
        File localEngine = new File(getFilesDir(), "libpikafish.so");
        try (java.io.FileInputStream in = new java.io.FileInputStream(nativeEngine);
             FileOutputStream out = new FileOutputStream(localEngine)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) > 0) {
                out.write(buffer, 0, count);
            }
        }
        if (!localEngine.setExecutable(true, true)) {
            throw new IOException("无法设置引擎可执行权限：" + localEngine);
        }
        return localEngine.getAbsolutePath();
    }

    // ---------------------------------------------------------------------
    // UciEngine.Listener
    // ---------------------------------------------------------------------

    @Override
    public void onReady() {
        engineReady = true;
        engineFailed = false;
        updateControls();
        setStatus(board.side == HUMAN ? "轮到你走棋（红方）" : "引擎思考中…");
    }

    @Override
    public void onError(String message) {
        onEngineError(message);
    }

    private void onEngineError(String message) {
        engineReady = false;
        engineFailed = true;
        busy = false;
        updateControls();
        setStatus("引擎不可用");
        analysisView.setText("引擎错误：\n" + message);
        if (!isFinishing()) {
            new AlertDialog.Builder(this)
                    .setTitle("引擎错误")
                    .setMessage(message)
                    .setPositiveButton("确定", null)
                    .show();
        }
    }

    // ---------------------------------------------------------------------
    // 对弈流程
    // ---------------------------------------------------------------------

    @Override
    public void onSquareTap(int r, int c) {
        if (!engineReady || busy || gameOver || board.side != HUMAN) {
            return;
        }
        char piece = board.grid[r][c];
        if (piece != '.' && Board.colorOf(piece) == HUMAN) {
            selectedR = r;
            selectedC = c;
            legalForSelected.clear();
            for (Board.Move move : board.legalMoves()) {
                if (move.fr == r && move.fc == c) {
                    legalForSelected.add(move);
                }
            }
            updateBoard();
            return;
        }
        if (selectedR >= 0) {
            Board.Move move = new Board.Move(selectedR, selectedC, r, c);
            if (legalForSelected.contains(move)) {
                clearSelection();
                doHumanMove(move);
                return;
            }
            clearSelection();
            updateBoard();
        }
    }

    private void doHumanMove(Board.Move move) {
        recordMove(move);
        updateBoard();
        if (checkGameOver()) {
            return;
        }
        startEngineMove();
    }

    private void startEngineMove() {
        if (!engineReady) {
            return;
        }
        busy = true;
        clearSelection();
        updateBoard();
        updateControls();
        setStatus("引擎思考中…");

        final char sideToMove = board.side;
        engine.search(board.fen(), difficultyMs(), new UciEngine.MoveListener() {
            @Override
            public void onInfo(UciEngine.Info info) {
                setStatus("引擎思考中… 深度 " + info.depth
                        + "  评分 " + scoreText(info, sideToMove));
            }

            @Override
            public void onBest(String best) {
                busy = false;
                if (gameOver) {
                    updateControls();
                    return;
                }
                if ("(none)".equals(best) || "0000".equals(best)) {
                    if (!checkGameOver()) {
                        onEngineError("引擎没有返回可走的着法。");
                    }
                    return;
                }
                Board.Move move;
                try {
                    move = Board.Move.parse(best);
                } catch (Exception e) {
                    onEngineError("无法解析引擎着法：" + best);
                    return;
                }
                if (!board.legalMoves().contains(move)) {
                    onEngineError("引擎返回了非法着法：" + best);
                    return;
                }
                recordMove(move);
                updateBoard();
                if (checkGameOver()) {
                    return;
                }
                setStatus("轮到你走棋（红方）");
                updateControls();
            }
        });
    }

    private boolean checkGameOver() {
        if (!board.legalMoves().isEmpty()) {
            return false;
        }
        boolean inCheck = board.isInCheck(board.side);
        String loser = board.side == Board.RED ? "红方" : "黑方";
        String winner = board.side == Board.RED ? "黑方" : "红方";
        resultText = winner + "胜（" + loser + (inCheck ? "被将死" : "无子可动") + "）";
        gameOver = true;
        busy = false;
        setStatus(resultText);
        updateControls();
        return true;
    }

    private void recordMove(Board.Move move) {
        HistoryEntry entry = new HistoryEntry();
        entry.side = board.side;
        entry.piece = board.grid[move.fr][move.fc];
        entry.fenBefore = board.fen();
        entry.move = move;
        entry.uci = move.uci();
        entry.captured = board.applyMove(move);
        history.add(entry);
        lastMove = move;
        updateMoveList();
    }

    private void newGame() {
        if (busy) {
            return;
        }
        board = new Board();
        history.clear();
        lastMove = null;
        gameOver = false;
        resultText = "";
        clearSelection();
        updateBoard();
        updateMoveList();
        updateControls();
        setStatus(engineReady ? "轮到你走棋（红方）"
                : (engineFailed ? "引擎不可用" : "正在启动引擎…"));
        if (engineReady) {
            engine.newGame();
        }
    }

    private void undo() {
        if (busy || history.isEmpty()) {
            return;
        }
        int count = history.get(history.size() - 1).side == HUMAN
                ? 1 : Math.min(2, history.size());
        HistoryEntry entry = null;
        for (int i = 0; i < count; i++) {
            entry = history.remove(history.size() - 1);
        }
        if (entry == null) {
            return;
        }
        board = new Board();
        board.loadFen(entry.fenBefore);
        gameOver = false;
        resultText = "";
        lastMove = history.isEmpty() ? null : history.get(history.size() - 1).move;
        clearSelection();
        updateBoard();
        updateMoveList();
        updateControls();
        setStatus("已悔棋。轮到你走棋（红方）");
    }

    private void startAnalysis() {
        if (busy || !engineReady) {
            return;
        }
        busy = true;
        clearSelection();
        updateBoard();
        updateControls();
        setStatus("分析中…");
        final String fen = board.fen();
        final char side = board.side;
        engine.analyze(fen, 2500, 3, new UciEngine.AnalysisListener() {
            @Override
            public void onInfo(UciEngine.Info info) {
                analysisView.setText("分析中…\n深度 " + info.depth
                        + "  评分 " + scoreText(info, side));
            }

            @Override
            public void onDone(List<UciEngine.Info> lines) {
                busy = false;
                updateControls();
                analysisView.setText(formatAnalysis(fen, lines));
                if (gameOver) {
                    setStatus(resultText);
                } else {
                    setStatus(board.side == HUMAN ? "轮到你走棋（红方）" : "引擎思考中…");
                }
            }
        });
    }

    // ---------------------------------------------------------------------
    // 界面刷新
    // ---------------------------------------------------------------------

    private void updateBoard() {
        boardView.setState(board, selectedR, selectedC, legalForSelected, lastMove);
    }

    private void updateMoveList() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < history.size(); i++) {
            HistoryEntry entry = history.get(i);
            int number = i / 2 + 1;
            sb.append(String.format(Locale.US, "%3d", number))
                    .append(entry.side == Board.RED ? ". " : "… ")
                    .append(entry.side == Board.RED ? "红方 " : "黑方 ")
                    .append(Board.pieceName(entry.piece)).append(' ')
                    .append(entry.uci.substring(0, 2)).append('-')
                    .append(entry.uci.substring(2, 4)).append('\n');
        }
        movesView.setText(sb.toString());
    }

    private void updateControls() {
        boolean idle = !busy;
        setControlEnabled(newButton, idle);
        setControlEnabled(undoButton, idle && !history.isEmpty());
        setControlEnabled(analyzeButton, idle && engineReady);
        strengthSpinner.setEnabled(engineReady);
        strengthSpinner.setAlpha(engineReady ? 1f : 0.45f);
    }

    private void setControlEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.45f);
    }

    private void clearSelection() {
        selectedR = -1;
        selectedC = -1;
        legalForSelected.clear();
    }

    private int difficultyMs() {
        int index = strengthSpinner.getSelectedItemPosition();
        if (index < 0 || index >= DIFFICULTY_MS.length) {
            index = 1;
        }
        return DIFFICULTY_MS[index];
    }

    // ---------------------------------------------------------------------
    // 文本格式化
    // ---------------------------------------------------------------------

    private String formatAnalysis(String fen, List<UciEngine.Info> lines) {
        if (lines.isEmpty()) {
            return "没有得到分析结果。";
        }
        Board start = new Board();
        start.loadFen(fen);
        char side = start.side;
        int maxDepth = 0;
        for (UciEngine.Info info : lines) {
            maxDepth = Math.max(maxDepth, info.depth);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(side == Board.RED ? "红方" : "黑方")
                .append("走棋 | 分析深度 ").append(maxDepth).append("\n\n");
        for (UciEngine.Info info : lines) {
            sb.append(info.multipv).append(". 评分 ")
                    .append(scoreText(info, side))
                    .append("   深度 ").append(info.depth).append('\n');
            String pv = formatPv(start, info.pv);
            if (!pv.isEmpty()) {
                sb.append("   ").append(pv).append('\n');
            }
            sb.append('\n');
        }
        sb.append("评分以红方视角显示");
        return sb.toString();
    }

    private static String formatPv(Board start, String pv) {
        Board board = start.copy();
        StringBuilder sb = new StringBuilder();
        for (String token : pv.split("\\s+")) {
            if (token.length() < 4) {
                break;
            }
            Board.Move move;
            try {
                move = Board.Move.parse(token);
            } catch (Exception e) {
                break;
            }
            char piece = board.grid[move.fr][move.fc];
            if (piece == '.') {
                break;
            }
            sb.append(Board.pieceName(piece)).append(' ')
                    .append(token, 0, 2).append('-').append(token, 2, 4)
                    .append('　');
            board.applyMove(move);
        }
        return sb.toString().trim();
    }

    private static String scoreText(UciEngine.Info info, char side) {
        if (info.scoreType == null) {
            return "—";
        }
        if ("mate".equals(info.scoreType)) {
            int n = side == Board.RED ? info.scoreValue : -info.scoreValue;
            if (n > 0) {
                return "红方 " + n + " 步杀";
            }
            if (n < 0) {
                return "黑方 " + (-n) + " 步杀";
            }
            return "杀棋";
        }
        int cp = side == Board.RED ? info.scoreValue : -info.scoreValue;
        return String.format(Locale.US, "%+.2f", cp / 100.0);
    }
}
