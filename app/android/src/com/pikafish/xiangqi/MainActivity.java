package com.pikafish.xiangqi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pikafish 象棋 Android 主界面。
 * 支持人机对弈、双引擎对战、外置引擎/权重导入、落子音效与步时记时。
 */
public class MainActivity extends Activity implements BoardView.Listener {

    private static final char HUMAN = Board.RED;
    private static final String PREFS = "pikafish_engine_settings";

    private static final int REQ_RED_ENGINE = 101;
    private static final int REQ_RED_NET = 102;
    private static final int REQ_BLACK_ENGINE = 201;
    private static final int REQ_BLACK_NET = 202;

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
    private char busySide = 0;
    private boolean gameOver = false;
    private boolean engineVsEngine = false;
    private boolean importing = false;
    private String resultText = "";
    private long generation = 0;

    private UciEngine redEngine;
    private UciEngine blackEngine;
    private boolean redReady = false;
    private boolean blackReady = false;
    private boolean redFailed = false;
    private boolean blackFailed = false;
    private boolean redErrorShown = false;
    private boolean blackErrorShown = false;
    private File bundledNet;

    private SharedPreferences prefs;
    private String redEnginePath = "";
    private String redEngineLabel = "";
    private String redNetPath = "";
    private String redNetLabel = "";
    private String blackEnginePath = "";
    private String blackEngineLabel = "";
    private String blackNetPath = "";
    private String blackNetLabel = "";

    private BoardView boardView;
    private TextView statusView;
    private TextView movesView;
    private TextView analysisView;
    private TextView redTimerView;
    private TextView blackTimerView;
    private TextView redEngineInfoView;
    private TextView blackEngineInfoView;
    private Button newButton;
    private Button undoButton;
    private Button analyzeButton;
    private Button settingsButton;
    private CheckBox engineVsEngineBox;
    private Spinner strengthSpinner;
    private AlertDialog settingsDialog;

    private SoundPool soundPool;
    private int moveSoundId;
    private boolean soundReady = false;

    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private Runnable timerRunnable;
    private long redTotalMs = 0;
    private long blackTotalMs = 0;
    private long redLastMoveMs = 0;
    private long blackLastMoveMs = 0;
    private long currentMoveStartMs = -1;
    private char timingSide = 0;

    private static class HistoryEntry {
        String uci;
        char piece;
        char captured;
        char side;
        String fenBefore;
        Board.Move move;
        long moveMs;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        loadEngineSettings();

        buildUi();
        initSound();
        resetClock();
        updateBoard();
        updateMoveList();
        updateControls();
        setStatus("正在准备引擎…");
        prepareEngines();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (timerRunnable != null) {
            timerHandler.removeCallbacks(timerRunnable);
            timerHandler.post(timerRunnable);
        }
    }

    @Override
    protected void onPause() {
        if (timerRunnable != null) {
            timerHandler.removeCallbacks(timerRunnable);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        generation++;
        timerHandler.removeCallbacksAndMessages(null);
        closeEngine(Board.RED);
        closeEngine(Board.BLACK);
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
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

        LinearLayout timerRow = new LinearLayout(this);
        timerRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams timerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        timerParams.topMargin = dp(6);
        redTimerView = makeTimerView(0xFFB52D1F, Gravity.START);
        blackTimerView = makeTimerView(0xFF1B1B1B, Gravity.END);
        LinearLayout.LayoutParams redTimerParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        redTimerParams.rightMargin = dp(6);
        timerRow.addView(redTimerView, redTimerParams);
        timerRow.addView(blackTimerView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(timerRow, timerParams);

        boardView = new BoardView(this);
        boardView.setListener(this);
        LinearLayout.LayoutParams boardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        boardParams.topMargin = dp(6);
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

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams modeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        modeParams.topMargin = dp(8);
        engineVsEngineBox = new CheckBox(this);
        engineVsEngineBox.setText("双引擎对战");
        engineVsEngineBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        engineVsEngineBox.setTextColor(0xFF5B3A1E);
        engineVsEngineBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked != engineVsEngine) {
                setEngineVsEngine(isChecked);
            }
        });
        modeRow.addView(engineVsEngineBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        settingsButton = makeButton("引擎设置");
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
        settingsParams.leftMargin = dp(10);
        modeRow.addView(settingsButton, settingsParams);
        root.addView(modeRow, modeParams);

        LinearLayout strengthRow = new LinearLayout(this);
        strengthRow.setOrientation(LinearLayout.HORIZONTAL);
        strengthRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams strengthParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        strengthParams.topMargin = dp(8);
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
        settingsButton.setOnClickListener(v -> showEngineSettings());

        root.addView(sectionLabel("着法记录"));
        movesView = createInfoText();
        ScrollView movesScroll = new ScrollView(this);
        movesScroll.addView(movesView, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(movesScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(96)));

        root.addView(sectionLabel("局面分析"));
        analysisView = createInfoText();
        ScrollView analysisScroll = new ScrollView(this);
        analysisScroll.addView(analysisView, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(analysisScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(140)));

        setContentView(scroll);

        timerRunnable = new Runnable() {
            @Override
            public void run() {
                updateTimerLabels();
                timerHandler.postDelayed(this, 100);
            }
        };
    }

    private TextView makeTimerView(int color, int gravity) {
        TextView view = new TextView(this);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        view.setTextColor(color);
        view.setGravity(gravity);
        view.setBackgroundColor(0xFFFFFDF7);
        view.setPadding(dp(8), dp(5), dp(8), dp(5));
        view.setLineSpacing(dp(1), 1f);
        return view;
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

    private Button makeSmallButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        button.setTextColor(Color.WHITE);
        button.setBackgroundTintList(ColorStateList.valueOf(0xFF8B5E34));
        button.setPadding(dp(6), 0, dp(6), 0);
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
    // 落子音效
    // ---------------------------------------------------------------------

    private void initSound() {
        try {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            soundPool = new SoundPool.Builder()
                    .setMaxStreams(4)
                    .setAudioAttributes(attributes)
                    .build();
            soundPool.setOnLoadCompleteListener((pool, sampleId, status) -> soundReady = status == 0);
            moveSoundId = soundPool.load(this, R.raw.move_sound, 1);
        } catch (Exception ignored) {
            soundPool = null;
        }
    }

    private void playMoveSound() {
        if (soundPool != null && soundReady) {
            soundPool.play(moveSoundId, 1f, 1f, 1, 0, 1f);
        }
    }

    // ---------------------------------------------------------------------
    // 引擎配置与生命周期
    // ---------------------------------------------------------------------

    private void loadEngineSettings() {
        redEnginePath = prefs.getString("red_engine_path", "");
        redEngineLabel = prefs.getString("red_engine_label", "");
        redNetPath = prefs.getString("red_net_path", "");
        redNetLabel = prefs.getString("red_net_label", "");
        blackEnginePath = prefs.getString("black_engine_path", "");
        blackEngineLabel = prefs.getString("black_engine_label", "");
        blackNetPath = prefs.getString("black_net_path", "");
        blackNetLabel = prefs.getString("black_net_label", "");
    }

    private static String sideName(char side) {
        return side == Board.RED ? "红方" : "黑方";
    }

    private static String sideFolder(char side) {
        return side == Board.RED ? "red" : "black";
    }

    private String enginePathPref(char side) {
        return side == Board.RED ? redEnginePath : blackEnginePath;
    }

    private String netPathPref(char side) {
        return side == Board.RED ? redNetPath : blackNetPath;
    }

    private String engineLabel(char side) {
        String path = enginePathPref(side);
        if (path.isEmpty()) {
            return "内置 Pikafish";
        }
        String label = side == Board.RED ? redEngineLabel : blackEngineLabel;
        return label.isEmpty() ? new File(path).getName() : label;
    }

    private String netLabel(char side) {
        String path = netPathPref(side);
        if (!path.isEmpty()) {
            String label = side == Board.RED ? redNetLabel : blackNetLabel;
            return label.isEmpty() ? new File(path).getName() : label;
        }
        return enginePathPref(side).isEmpty() ? "内置 NNUE" : "未设置（使用引擎内置网络）";
    }

    private UciEngine engineFor(char side) {
        return side == Board.RED ? redEngine : blackEngine;
    }

    private boolean isReady(char side) {
        return side == Board.RED ? redReady : blackReady;
    }

    private boolean isFailed(char side) {
        return side == Board.RED ? redFailed : blackFailed;
    }

    private void setReady(char side, boolean ready) {
        if (side == Board.RED) {
            redReady = ready;
        } else {
            blackReady = ready;
        }
    }

    private void setFailed(char side, boolean failed) {
        if (side == Board.RED) {
            redFailed = failed;
        } else {
            blackFailed = failed;
        }
    }

    private void prepareEngines() {
        new Thread(() -> {
            try {
                bundledNet = ensureBundledNet();
            } catch (Exception e) {
                final String message = "初始化失败：" + describe(e);
                runOnUiThread(() -> fatalEngineError(message));
                return;
            }
            runOnUiThread(() -> {
                startEngine(Board.RED);
                startEngine(Board.BLACK);
            });
        }, "engine-setup").start();
    }

    private File ensureBundledNet() throws IOException {
        File net = new File(getFilesDir(), "pikafish.nnue");
        File marker = new File(getFilesDir(), "net.ok");
        if (!net.exists() || !marker.exists() || net.length() < 10_000_000L) {
            copyAsset("pikafish.nnue", net);
            if (!marker.exists() && !marker.createNewFile()) {
                throw new IOException("无法写入引擎标记文件");
            }
        }
        return net;
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

    private void startEngine(char side) {
        closeEngine(side);
        boolean custom = !enginePathPref(side).isEmpty();
        String enginePath = custom ? enginePathPref(side)
                : getApplicationInfo().nativeLibraryDir + "/libpikafish.so";
        String netPath;
        if (!netPathPref(side).isEmpty()) {
            netPath = netPathPref(side);
        } else if (custom) {
            netPath = null;
        } else {
            netPath = bundledNet != null ? bundledNet.getAbsolutePath() : null;
        }
        if (!new File(enginePath).exists()) {
            onEngineError(side, "找不到引擎文件：" + enginePath);
            return;
        }
        if (netPath != null && !new File(netPath).exists()) {
            onEngineError(side, "找不到权重文件：" + netPath);
            return;
        }

        final UciEngine engine = new UciEngine();
        if (side == Board.RED) {
            redEngine = engine;
            redReady = false;
            redFailed = false;
            redErrorShown = false;
        } else {
            blackEngine = engine;
            blackReady = false;
            blackFailed = false;
            blackErrorShown = false;
        }
        updateEngineInfoViews();
        engine.start(enginePath, netPath, new UciEngine.Listener() {
            @Override
            public void onReady() {
                if (engineFor(side) != engine) {
                    return;
                }
                setReady(side, true);
                setFailed(side, false);
                updateEngineInfoViews();
                updateControls();
                maybeStartEngineTurn();
            }

            @Override
            public void onError(String message) {
                if (engineFor(side) != engine) {
                    return;
                }
                setReady(side, false);
                onEngineError(side, message);
            }
        });
    }

    private void closeEngine(char side) {
        UciEngine engine = engineFor(side);
        if (side == Board.RED) {
            redEngine = null;
            redReady = false;
            redFailed = false;
        } else {
            blackEngine = null;
            blackReady = false;
            blackFailed = false;
        }
        if (engine != null) {
            engine.close();
        }
    }

    private void onEngineError(char side, String message) {
        setReady(side, false);
        setFailed(side, true);
        if (busy && busySide == side) {
            busy = false;
            busySide = 0;
        }
        updateEngineInfoViews();
        updateControls();
        setStatus(sideName(side) + "引擎不可用");
        boolean alreadyShown = side == Board.RED ? redErrorShown : blackErrorShown;
        if (alreadyShown || isFinishing()) {
            return;
        }
        if (side == Board.RED) {
            redErrorShown = true;
        } else {
            blackErrorShown = true;
        }
        new AlertDialog.Builder(this)
                .setTitle(sideName(side) + "引擎错误")
                .setMessage(message)
                .setPositiveButton("确定", null)
                .show();
    }

    private void fatalEngineError(String message) {
        redFailed = true;
        blackFailed = true;
        setStatus("引擎不可用");
        updateControls();
        if (!isFinishing()) {
            new AlertDialog.Builder(this)
                    .setTitle("引擎初始化失败")
                    .setMessage(message)
                    .setPositiveButton("确定", null)
                    .show();
        }
    }

    private void cancelEngines() {
        generation++;
        busy = false;
        busySide = 0;
        if (redEngine != null) {
            redEngine.stopSearch();
        }
        if (blackEngine != null) {
            blackEngine.stopSearch();
        }
        updateControls();
    }

    private void updateEngineInfoViews() {
        if (redEngineInfoView != null) {
            redEngineInfoView.setText(engineInfo(Board.RED));
        }
        if (blackEngineInfoView != null) {
            blackEngineInfoView.setText(engineInfo(Board.BLACK));
        }
    }

    private String engineInfo(char side) {
        String state;
        if (isFailed(side)) {
            state = "加载失败，请检查引擎设置";
        } else if (isReady(side)) {
            state = "已就绪";
        } else if (engineFor(side) != null) {
            state = "加载中…";
        } else {
            state = "未加载";
        }
        return "引擎：" + engineLabel(side) + "\n"
                + "权重：" + netLabel(side) + "\n"
                + "状态：" + state;
    }

    private void maybeStartEngineTurn() {
        if (gameOver || busy || importing) {
            return;
        }
        char side = board.side;
        if (engineVsEngine) {
            if (isReady(side)) {
                startEngineTurn();
            } else if (isFailed(side)) {
                setStatus(sideName(side) + "引擎不可用");
            } else {
                setStatus("等待" + sideName(side) + "引擎加载…");
            }
            return;
        }
        if (side == HUMAN) {
            setStatus("轮到你走棋（红方）");
            return;
        }
        if (isReady(Board.BLACK)) {
            startEngineTurn();
        } else if (isFailed(Board.BLACK)) {
            setStatus("黑方引擎不可用，请检查引擎设置");
        } else {
            setStatus("等待黑方引擎加载…");
        }
    }

    // ---------------------------------------------------------------------
    // 引擎设置界面
    // ---------------------------------------------------------------------

    private void showEngineSettings() {
        if (importing) {
            return;
        }
        if (settingsDialog != null && settingsDialog.isShowing()) {
            return;
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(12), dp(18), dp(8));

        TextView note = new TextView(this);
        note.setText("导入的引擎需为 Android ARM64 可执行文件（ELF PIE），权重文件通常为 .nnue。"
                + "文件会保存到应用私有目录，重启后仍然有效。");
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        note.setTextColor(0xFF6B5B4A);
        note.setLineSpacing(dp(2), 1f);
        content.addView(note);

        addEngineSection(content, Board.RED);
        addEngineSection(content, Board.BLACK);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        settingsDialog = new AlertDialog.Builder(this)
                .setTitle("引擎设置")
                .setView(scroll)
                .setPositiveButton("关闭", null)
                .create();
        settingsDialog.setOnDismissListener(dialog -> {
            redEngineInfoView = null;
            blackEngineInfoView = null;
            settingsDialog = null;
        });
        settingsDialog.show();
    }

    private void addEngineSection(LinearLayout parent, char side) {
        parent.addView(sectionLabel(sideName(side) + "引擎"));
        TextView info = createInfoText();
        info.setText(engineInfo(side));
        if (side == Board.RED) {
            redEngineInfoView = info;
        } else {
            blackEngineInfoView = info;
        }
        parent.addView(info);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        rowParams.topMargin = dp(8);

        Button engineButton = makeSmallButton("导入引擎");
        Button netButton = makeSmallButton("导入权重");
        Button resetButton = makeSmallButton("恢复默认");
        engineButton.setOnClickListener(v -> pickFile(side, true));
        netButton.setOnClickListener(v -> pickFile(side, false));
        resetButton.setOnClickListener(v -> confirmResetEngine(side));

        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(0, dp(40), 1f);
        row.addView(engineButton, buttonParams);
        LinearLayout.LayoutParams buttonParams2 = new LinearLayout.LayoutParams(0, dp(40), 1f);
        buttonParams2.leftMargin = dp(6);
        row.addView(netButton, buttonParams2);
        LinearLayout.LayoutParams buttonParams3 = new LinearLayout.LayoutParams(0, dp(40), 1f);
        buttonParams3.leftMargin = dp(6);
        row.addView(resetButton, buttonParams3);
        parent.addView(row, rowParams);
    }

    private void pickFile(char side, boolean engineFile) {
        if (importing) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        int code;
        if (side == Board.RED) {
            code = engineFile ? REQ_RED_ENGINE : REQ_RED_NET;
        } else {
            code = engineFile ? REQ_BLACK_ENGINE : REQ_BLACK_NET;
        }
        try {
            startActivityForResult(intent, code);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开文件选择器：" + describe(e), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        char side;
        boolean engineFile;
        if (requestCode == REQ_RED_ENGINE || requestCode == REQ_RED_NET) {
            side = Board.RED;
            engineFile = requestCode == REQ_RED_ENGINE;
        } else if (requestCode == REQ_BLACK_ENGINE || requestCode == REQ_BLACK_NET) {
            side = Board.BLACK;
            engineFile = requestCode == REQ_BLACK_ENGINE;
        } else {
            return;
        }
        importFile(side, engineFile, data.getData());
    }

    private void importFile(char side, boolean engineFile, Uri uri) {
        if (importing) {
            return;
        }
        cancelEngines();
        closeEngine(side);
        importing = true;
        updateControls();
        setStatus("正在导入" + (engineFile ? "引擎" : "权重") + "…");
        final String displayName = queryDisplayName(uri);

        new Thread(() -> {
            try {
                File dir = new File(getFilesDir(), "engines/" + sideFolder(side));
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IOException("无法创建目录");
                }
                File target = new File(dir, engineFile ? "engine.bin" : "net.nnue");
                File temp = new File(dir, target.getName() + ".tmp");
                copyUri(uri, temp);
                if (engineFile) {
                    if (!temp.setExecutable(true, true)) {
                        throw new IOException("无法设置引擎执行权限");
                    }
                    EngineFileChecker.validate(temp);
                }
                if (target.exists() && !target.delete()) {
                    throw new IOException("无法覆盖旧文件");
                }
                if (!temp.renameTo(target)) {
                    throw new IOException("无法保存文件");
                }
                prefs.edit()
                        .putString(engineFile ? keyEnginePath(side) : keyNetPath(side),
                                target.getAbsolutePath())
                        .putString(engineFile ? keyEngineLabel(side) : keyNetLabel(side),
                                displayName)
                        .apply();
                runOnUiThread(() -> {
                    importing = false;
                    loadEngineSettings();
                    updateEngineInfoViews();
                    updateControls();
                    Toast.makeText(this, "已导入：" + displayName, Toast.LENGTH_SHORT).show();
                    startEngine(side);
                    maybeStartEngineTurn();
                });
            } catch (Exception e) {
                final String message = describe(e);
                runOnUiThread(() -> {
                    importing = false;
                    updateControls();
                    Toast.makeText(this, "导入失败：" + message, Toast.LENGTH_LONG).show();
                    startEngine(side);
                    maybeStartEngineTurn();
                });
            }
        }, "engine-import").start();
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (Exception ignored) {
            // 使用路径作为后备
        }
        String path = uri.getLastPathSegment();
        return path == null ? "engine-file" : path;
    }

    private void copyUri(Uri uri, File target) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(target)) {
            if (in == null) {
                throw new IOException("无法读取所选文件");
            }
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) > 0) {
                out.write(buffer, 0, count);
            }
        }
    }

    private static String keyEnginePath(char side) {
        return side == Board.RED ? "red_engine_path" : "black_engine_path";
    }

    private static String keyEngineLabel(char side) {
        return side == Board.RED ? "red_engine_label" : "black_engine_label";
    }

    private static String keyNetPath(char side) {
        return side == Board.RED ? "red_net_path" : "black_net_path";
    }

    private static String keyNetLabel(char side) {
        return side == Board.RED ? "red_net_label" : "black_net_label";
    }

    private void confirmResetEngine(char side) {
        new AlertDialog.Builder(this)
                .setTitle(sideName(side) + "恢复内置引擎")
                .setMessage("将删除该方已导入的引擎和权重文件，恢复使用内置 Pikafish。")
                .setPositiveButton("恢复", (dialog, which) -> resetEngine(side))
                .setNegativeButton("取消", null)
                .show();
    }

    private void resetEngine(char side) {
        cancelEngines();
        closeEngine(side);
        prefs.edit()
                .remove(keyEnginePath(side))
                .remove(keyEngineLabel(side))
                .remove(keyNetPath(side))
                .remove(keyNetLabel(side))
                .apply();
        loadEngineSettings();
        deleteRecursively(new File(getFilesDir(), "engines/" + sideFolder(side)));
        updateEngineInfoViews();
        updateControls();
        startEngine(side);
        maybeStartEngineTurn();
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }

    // ---------------------------------------------------------------------
    // 对弈流程
    // ---------------------------------------------------------------------

    @Override
    public void onSquareTap(int r, int c) {
        if (importing || busy || gameOver || engineVsEngine || board.side != HUMAN) {
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
        playMoveSound();
        if (checkGameOver()) {
            return;
        }
        maybeStartEngineTurn();
    }

    private void setEngineVsEngine(boolean enabled) {
        if (importing) {
            engineVsEngineBox.setChecked(engineVsEngine);
            return;
        }
        engineVsEngine = enabled;
        cancelEngines();
        clearSelection();
        updateBoard();
        updateControls();
        if (enabled) {
            setStatus("双引擎对战");
            maybeStartEngineTurn();
        } else {
            maybeStartEngineTurn();
            if (!busy) {
                setStatus(board.side == HUMAN ? "轮到你走棋（红方）" : "黑方引擎思考中…");
            }
        }
    }

    private void startEngineTurn() {
        if (gameOver || busy || importing) {
            return;
        }
        final char side = board.side;
        if (!engineVsEngine && side == HUMAN) {
            setStatus("轮到你走棋（红方）");
            return;
        }
        if (!isReady(side)) {
            setStatus((isFailed(side) ? sideName(side) + "引擎不可用"
                    : "等待" + sideName(side) + "引擎加载…"));
            return;
        }
        final UciEngine engine = engineFor(side);
        if (engine == null) {
            return;
        }
        busy = true;
        busySide = side;
        final long gen = ++generation;
        clearSelection();
        updateBoard();
        updateControls();
        setStatus(sideName(side) + "引擎思考中…");

        engine.search(board.fen(), difficultyMs(), new UciEngine.MoveListener() {
            @Override
            public void onInfo(UciEngine.Info info) {
                if (gen != generation || engineFor(side) != engine) {
                    return;
                }
                setStatus(sideName(side) + "思考中… 深度 " + info.depth
                        + "  评分 " + scoreText(info, side));
            }

            @Override
            public void onBest(String best) {
                if (gen != generation || engineFor(side) != engine) {
                    return;
                }
                busy = false;
                busySide = 0;
                if (gameOver) {
                    updateControls();
                    return;
                }
                if ("(none)".equals(best) || "0000".equals(best)) {
                    if (!checkGameOver()) {
                        onEngineError(side, "引擎没有返回可走的着法。");
                    }
                    return;
                }
                Board.Move move;
                try {
                    move = Board.Move.parse(best);
                } catch (Exception e) {
                    onEngineError(side, "无法解析引擎着法：" + best);
                    return;
                }
                if (!board.legalMoves().contains(move)) {
                    onEngineError(side, "引擎返回了非法着法：" + best);
                    return;
                }
                recordMove(move);
                updateBoard();
                playMoveSound();
                if (checkGameOver()) {
                    return;
                }
                maybeStartEngineTurn();
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
        busySide = 0;
        timingSide = 0;
        currentMoveStartMs = -1;
        updateTimerLabels();
        setStatus(resultText);
        updateControls();
        return true;
    }

    private void recordMove(Board.Move move) {
        long now = SystemClock.elapsedRealtime();
        char mover = board.side;
        long moveMs = (timingSide == mover && currentMoveStartMs > 0)
                ? Math.max(0, now - currentMoveStartMs) : 0;

        HistoryEntry entry = new HistoryEntry();
        entry.side = mover;
        entry.piece = board.grid[move.fr][move.fc];
        entry.fenBefore = board.fen();
        entry.move = move;
        entry.uci = move.uci();
        entry.moveMs = moveMs;
        entry.captured = board.applyMove(move);
        history.add(entry);
        lastMove = move;

        if (mover == Board.RED) {
            redTotalMs += moveMs;
            redLastMoveMs = moveMs;
        } else {
            blackTotalMs += moveMs;
            blackLastMoveMs = moveMs;
        }
        timingSide = board.side;
        currentMoveStartMs = now;
        updateTimerLabels();
        updateMoveList();
    }

    private void newGame() {
        cancelEngines();
        board = new Board();
        history.clear();
        lastMove = null;
        gameOver = false;
        resultText = "";
        clearSelection();
        resetClock();
        updateBoard();
        updateMoveList();
        updateControls();
        maybeStartEngineTurn();
    }

    private void undo() {
        if (history.isEmpty() || importing) {
            return;
        }
        cancelEngines();
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
        recomputeClock();
        updateBoard();
        updateMoveList();
        updateControls();
        maybeStartEngineTurn();
    }

    private void startAnalysis() {
        if (busy || importing || engineVsEngine || !blackReady) {
            return;
        }
        final UciEngine engine = blackEngine;
        if (engine == null) {
            return;
        }
        busy = true;
        busySide = 0;
        final long gen = ++generation;
        clearSelection();
        updateBoard();
        updateControls();
        setStatus("分析中…");
        final String fen = board.fen();
        final char side = board.side;
        engine.analyze(fen, 2500, 3, new UciEngine.AnalysisListener() {
            @Override
            public void onInfo(UciEngine.Info info) {
                if (gen != generation || engineFor(Board.BLACK) != engine) {
                    return;
                }
                analysisView.setText("分析中…\n深度 " + info.depth
                        + "  评分 " + scoreText(info, side));
            }

            @Override
            public void onDone(List<UciEngine.Info> lines) {
                if (gen != generation || engineFor(Board.BLACK) != engine) {
                    return;
                }
                busy = false;
                busySide = 0;
                updateControls();
                analysisView.setText(formatAnalysis(fen, lines));
                if (gameOver) {
                    setStatus(resultText);
                } else {
                    maybeStartEngineTurn();
                }
            }
        });
    }

    // ---------------------------------------------------------------------
    // 记时
    // ---------------------------------------------------------------------

    private void resetClock() {
        redTotalMs = 0;
        blackTotalMs = 0;
        redLastMoveMs = 0;
        blackLastMoveMs = 0;
        timingSide = gameOver ? 0 : board.side;
        currentMoveStartMs = SystemClock.elapsedRealtime();
        updateTimerLabels();
    }

    private void recomputeClock() {
        redTotalMs = 0;
        blackTotalMs = 0;
        redLastMoveMs = 0;
        blackLastMoveMs = 0;
        for (HistoryEntry entry : history) {
            if (entry.side == Board.RED) {
                redTotalMs += entry.moveMs;
                redLastMoveMs = entry.moveMs;
            } else {
                blackTotalMs += entry.moveMs;
                blackLastMoveMs = entry.moveMs;
            }
        }
        timingSide = gameOver ? 0 : board.side;
        currentMoveStartMs = SystemClock.elapsedRealtime();
        updateTimerLabels();
    }

    private void updateTimerLabels() {
        if (redTimerView == null || blackTimerView == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long redCurrent = 0;
        long blackCurrent = 0;
        if (timingSide == Board.RED && currentMoveStartMs > 0) {
            redCurrent = Math.max(0, now - currentMoveStartMs);
        } else if (timingSide == Board.BLACK && currentMoveStartMs > 0) {
            blackCurrent = Math.max(0, now - currentMoveStartMs);
        }
        long redMove = timingSide == Board.RED ? redCurrent : redLastMoveMs;
        long blackMove = timingSide == Board.BLACK ? blackCurrent : blackLastMoveMs;
        long redTotal = redTotalMs + redCurrent;
        long blackTotal = blackTotalMs + blackCurrent;

        redTimerView.setText("红方 " + (timingSide == Board.RED ? "本步 " : "上步 ")
                + formatTime(redMove) + "\n总 " + formatTime(redTotal));
        blackTimerView.setText("黑方 " + (timingSide == Board.BLACK ? "本步 " : "上步 ")
                + formatTime(blackMove) + "\n总 " + formatTime(blackTotal));
    }

    private static String formatTime(long ms) {
        long value = Math.max(0, ms);
        long minutes = value / 60000;
        double seconds = (value % 60000) / 1000.0;
        if (minutes > 0) {
            return String.format(Locale.US, "%d:%04.1f", minutes, seconds);
        }
        return String.format(Locale.US, "%.1fs", seconds);
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
        boolean idle = !busy && !importing;
        setControlEnabled(newButton, !importing);
        setControlEnabled(undoButton, !importing && !history.isEmpty());
        setControlEnabled(analyzeButton, idle && !engineVsEngine && blackReady);
        setControlEnabled(settingsButton, !importing);
        engineVsEngineBox.setEnabled(!importing);
        strengthSpinner.setEnabled(!importing);
        strengthSpinner.setAlpha(importing ? 0.45f : 1f);
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

    private static String describe(Exception e) {
        if (e == null) {
            return "未知错误";
        }
        String message = e.getMessage();
        return message == null ? e.toString() : message;
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
