package com.ollama.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 主界面：液态玻璃（Liquid Glass）风格。
 * 顶部液态玻璃标题栏 → 运行日志（挪到上方）→ 中部内容区
 * （状态、启动/停止玻璃按钮、模型管理、气泡式对话）。
 * 对话中模型的思考过程（reasoning）以粗体显示在模型气泡内。
 */
public class MainActivity extends Activity {

    // ---- 配色（液态玻璃 + 酷安风格绿；背景压深一档让白色高光有对比） ----
    private static final int C_BG_TOP = 0xFFEAF0F8;
    private static final int C_BG_BOTTOM = 0xFFD7E1EF;
    private static final int C_TEXT = 0xFF1F2937;
    private static final int C_TEXT_SUB = 0xFF6B7280;
    private static final int C_GREEN_A = 0xFF3ED67E;
    private static final int C_GREEN_B = 0xFF1CA85A;
    private static final int C_RED = 0xFFEF4444;
    private static final int C_AMBER = 0xFFF59E0B;
    private static final int C_GLASS = 0xE8FFFFFF;   // 玻璃卡底色（半透明白）
    private static final int C_GLASS_STROKE = 0x88FFFFFF; // 高光描边
    private static final int C_LOG_BG = 0xE6121212;  // 日志面板：深色半透明玻璃

    private TextView statusView;
    private TextView statusDot;
    private LinearLayout chatContainer;
    private TextView logView;
    private View logPanel;
    private Button startBtn;
    private Button stopBtn;
    private Button logToggleBtn;
    private View logBody;
    private View logExpandBar;
    private EditText modelEdit;
    private EditText promptEdit;
    private TextView pendingBubble; // “思考中…”占位气泡

    private static final int REQ_STORAGE = 100;
    private boolean logFileNotified = false;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (OllamaService.ACTION_LOG.equals(action)) {
                refreshLog();
            } else if (OllamaService.ACTION_STATUS.equals(action)) {
                refreshStatus(intent.getStringExtra(OllamaService.EXTRA_STATE));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        requestNotificationPermissionIfNeeded();
        requestStoragePermissionIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter();
        f.addAction(OllamaService.ACTION_LOG);
        f.addAction(OllamaService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, f);
        }
        refreshLog();
        refreshStatus(OllamaService.isRunning() ? OllamaService.STATE_RUNNING : OllamaService.STATE_STOPPED);
        // 确保 ollama-log 目录已创建（日志文件在启动服务时才生成，见 LogFile.init）
        LogFile.ensureDir(this);
        if (LogFile.getDirPath() != null && !logFileNotified) {
            logFileNotified = true;
            Toast.makeText(this, "日志将保存至 " + LogFile.getDirPath(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
    }

    // ================= UI =================

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{C_BG_TOP, C_BG_BOTTOM}));

        // 背景氛围色块（让玻璃透出色彩，仿酷安质感）
        root.addView(orb(new int[]{0x38C6F0D0, 0x00C6F0D0}, dp(110)),
                new FrameLayout.LayoutParams(dp(220), dp(220)));
        root.addView(orb(new int[]{0x3094B9FF, 0x0094B9FF}, dp(90)),
                new FrameLayout.LayoutParams(dp(180), dp(180)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        col.addView(buildHeader());

        // 日志挪到上面：标题栏正下方
        logPanel = buildLogPanel();
        logExpandBar = buildLogExpandBar();
        col.addView(logPanel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));
        col.addView(logExpandBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(6), dp(14), dp(16));

        body.addView(buildStatusCard());
        body.addView(buildControlCard());
        body.addView(buildModelCard());
        body.addView(buildChatCard());

        scroller.addView(body, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        boolean collapsed = Prefs.logCollapsed(this);
        logPanel.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        logExpandBar.setVisibility(collapsed ? View.VISIBLE : View.GONE);
        return root;
    }

    /** 液态玻璃标题栏。 */
    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(14), dp(14), dp(12));

        TextView title = new TextView(this);
        title.setText("Ollama");
        title.setTextSize(21);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(C_TEXT);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView settings = new TextView(this);
        settings.setText("\u2699 设置");
        settings.setTextSize(14);
        settings.setTypeface(null, Typeface.BOLD);
        settings.setTextColor(C_TEXT);
        settings.setGravity(Gravity.CENTER);
        settings.setPadding(dp(14), dp(7), dp(14), dp(7));

        GradientDrawable sBody = glassRound(dp(22), 0xC8FFFFFF, 0xF0FFFFFF);
        // 左上受光（玻璃光泽）
        GradientDrawable sLight = new GradientDrawable();
        sLight.setCornerRadius(dp(22));
        sLight.setOrientation(GradientDrawable.Orientation.TL_BR);
        sLight.setColors(new int[]{0x4DFFFFFF, 0x00FFFFFF});
        android.graphics.drawable.LayerDrawable sLd =
                new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{sBody, sLight});
        settings.setBackground(sLd);

        settings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        header.addView(settings);
        return header;
    }

    private View buildStatusCard() {
        LinearLayout card = glassCard();
        card.setPadding(dp(16), dp(13), dp(16), dp(13));
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);

        statusDot = new TextView(this);
        statusDot.setText("\u25CF");
        statusDot.setTextSize(18);
        statusDot.setTextColor(Color.GRAY);

        statusView = new TextView(this);
        statusView.setText("服务未启动");
        statusView.setTextSize(15);
        statusView.setTypeface(null, Typeface.BOLD);
        statusView.setTextColor(C_TEXT);
        statusView.setPadding(dp(10), 0, 0, 0);

        card.addView(statusDot);
        card.addView(statusView, new LinearLayout.LayoutParams(0, -2, 1f));
        return card;
    }

    /** 液态玻璃启动/停止按钮。 */
    private View buildControlCard() {
        LinearLayout card = glassCard();
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setOrientation(LinearLayout.VERTICAL);

        startBtn = new Button(this);
        startBtn.setText("启 动 服 务");
        styleGlassButton(startBtn, new int[]{C_GREEN_A, C_GREEN_B}, Color.WHITE);
        startBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startOllama(); }
        });
        card.addView(startBtn, matchWidth(-2));

        stopBtn = new Button(this);
        stopBtn.setText("停止服务");
        styleGlassButton(stopBtn, null, C_TEXT);
        stopBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stopOllama(); }
        });
        LinearLayout.LayoutParams sp = matchWidth(-2);
        sp.topMargin = dp(6);
        card.addView(stopBtn, sp);
        return card;
    }

    private View buildModelCard() {
        LinearLayout card = glassCard();
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setOrientation(LinearLayout.VERTICAL);

        card.addView(sectionLabel("模型管理"));

        modelEdit = new EditText(this);
        modelEdit.setHint("模型名，如 qwen2.5:1.5b");
        modelEdit.setSingleLine(true);
        modelEdit.setTextSize(15);
        modelEdit.setTextColor(C_TEXT);
        modelEdit.setHintTextColor(C_TEXT_SUB);
        modelEdit.setPadding(dp(12), dp(10), dp(12), dp(10));
        modelEdit.setBackground(glassRound(dp(16), 0xAAFFFFFF, 0x66FFFFFF));
        card.addView(modelEdit, matchWidth(-2));

        Button pullBtn = new Button(this);
        pullBtn.setText("拉取模型（需联网，较大较慢）");
        styleGlassButton(pullBtn, null, C_TEXT);
        pullBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pullModel(); }
        });
        card.addView(pullBtn, matchWidth(-2));
        return card;
    }

    /** 对话卡片：输入区 + 气泡式对话区（思考过程粗体写在模型气泡里）。 */
    private View buildChatCard() {
        LinearLayout card = glassCard();
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setOrientation(LinearLayout.VERTICAL);

        card.addView(sectionLabel("对话"));

        promptEdit = new EditText(this);
        promptEdit.setHint("输入问题…");
        promptEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        promptEdit.setTextSize(15);
        promptEdit.setTextColor(C_TEXT);
        promptEdit.setHintTextColor(C_TEXT_SUB);
        promptEdit.setMinLines(2);
        promptEdit.setMaxLines(4);
        promptEdit.setPadding(dp(12), dp(10), dp(12), dp(10));
        promptEdit.setBackground(glassRound(dp(16), 0xAAFFFFFF, 0x66FFFFFF));
        card.addView(promptEdit, matchWidth(-2));

        Button sendBtn = new Button(this);
        sendBtn.setText("发 送");
        styleGlassButton(sendBtn, new int[]{C_GREEN_A, C_GREEN_B}, Color.WHITE);
        sendBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendChat(); }
        });
        card.addView(sendBtn, matchWidth(-2));

        chatContainer = new LinearLayout(this);
        chatContainer.setOrientation(LinearLayout.VERTICAL);
        TextView hint = new TextView(this);
        hint.setText("对话会显示在这里\n（模型带思考过程时会以粗体写在气泡内）");
        hint.setTextSize(13);
        hint.setTextColor(C_TEXT_SUB);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(16), 0, dp(8));
        chatContainer.addView(hint);
        card.addView(chatContainer, matchWidth(-2));
        return card;
    }

    /** 日志面板（现在位于页面顶部标题栏之下）。 */
    private View buildLogPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(round(C_LOG_BG, 0));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), dp(5), dp(6), dp(5));

        TextView label = new TextView(this);
        label.setText("运行日志");
        label.setTextSize(13);
        label.setTypeface(null, Typeface.BOLD);
        label.setTextColor(Color.WHITE);
        bar.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));

        Button clear = new Button(this);
        clear.setText("清空");
        styleLogBtn(clear);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { clearLog(); }
        });
        bar.addView(clear);

        logToggleBtn = new Button(this);
        logToggleBtn.setText("收起");
        styleLogBtn(logToggleBtn);
        logToggleBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleLog(); }
        });
        bar.addView(logToggleBtn);

        panel.addView(bar);

        ScrollView logScroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setText("（暂无日志）");
        logView.setTextSize(12);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextColor(0xFFD4D4D4);
        logView.setTextIsSelectable(true);
        logView.setPadding(dp(12), dp(6), dp(12), dp(8));
        logScroll.addView(logView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        logBody = logScroll;
        panel.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return panel;
    }

    /** 收起日志后保留的一条细展开条。 */
    private View buildLogExpandBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setBackground(round(C_LOG_BG, 0));

        TextView t = new TextView(this);
        t.setText("\u25B2 展开日志");
        t.setTextSize(12);
        t.setTypeface(null, Typeface.BOLD);
        t.setTextColor(Color.WHITE);
        t.setPadding(0, dp(6), 0, dp(6));
        bar.addView(t);

        bar.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleLog(); }
        });
        return bar;
    }

    // ================= 样式工具（液态玻璃） =================

    private View orb(int[] colors, int radius) {
        View v = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColors(colors);
        v.setBackground(d);
        return v;
    }

    /** 液态玻璃卡片：半透明白底 + 高光描边 + 悬浮阴影。 */
    private LinearLayout glassCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(glassRound(dp(18), C_GLASS, C_GLASS_STROKE));
        if (Build.VERSION.SDK_INT >= 28) {
            c.setElevation(dp(4));
            c.setOutlineAmbientShadowColor(0x1F000000);
            c.setOutlineSpotShadowColor(0x22000000);
        } else {
            c.setElevation(dp(3));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), 0, dp(8));
        c.setLayoutParams(lp);
        return c;
    }

    /** 液态玻璃圆角背景：半透明白 + 顶部高光描边。colors 为 null 时用半透明白。 */
    private GradientDrawable glassRound(int radius, int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        if (fill != -1) {
            d.setColor(fill);
        }
        d.setCornerRadius(radius);
        d.setStroke(dp(1), stroke);
        return d;
    }

    /** 内高光：主体边缘内侧极细白线 + 左上受光渐变（光源方向），非外圈边框。 */
    private void styleGlassButton(Button b, int[] colors, int textColor) {
        b.setAllCaps(false);
        b.setTextColor(textColor);
        b.setTextSize(15);
        b.setTypeface(null, Typeface.BOLD);

        GradientDrawable body = new GradientDrawable();
        body.setCornerRadius(dp(24));
        if (colors != null) {
            body.setOrientation(GradientDrawable.Orientation.TL_BR);
            body.setColors(colors);
            body.setStroke(hairline(), 0xB3FFFFFF);   // 细内描边
        } else {
            body.setColor(0xC8FFFFFF);
            body.setStroke(hairline(), 0xF0FFFFFF);   // 细内描边（玻璃按钮更亮）
        }

        // 光照层：左上受光，右下透明（玻璃光泽）
        GradientDrawable light = new GradientDrawable();
        light.setCornerRadius(dp(24));
        light.setOrientation(GradientDrawable.Orientation.TL_BR);
        light.setColors(new int[]{colors != null ? 0x33FFFFFF : 0x4DFFFFFF, 0x00FFFFFF});

        android.graphics.drawable.LayerDrawable ld =
                new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{body, light});
        b.setBackground(ld);
        b.setPadding(dp(10), dp(13), dp(10), dp(13));
        if (Build.VERSION.SDK_INT >= 28) {
            b.setElevation(dp(5));
            b.setOutlineAmbientShadowColor(0x33000000);
            b.setOutlineSpotShadowColor(0x33000000);
        }
    }

    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTypeface(null, Typeface.BOLD);
        t.setTextColor(C_TEXT_SUB);
        t.setPadding(0, 0, 0, dp(8));
        return t;
    }

    private void styleLogBtn(Button b) {
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTextColor(Color.WHITE);

        GradientDrawable body = new GradientDrawable();
        body.setCornerRadius(dp(14));
        body.setColor(0xFF3A3A3A);
        body.setStroke(hairline(), 0x80FFFFFF);   // 深色面板上的细内高光

        // 左上受光（玻璃光泽）
        GradientDrawable light = new GradientDrawable();
        light.setCornerRadius(dp(14));
        light.setOrientation(GradientDrawable.Orientation.TL_BR);
        light.setColors(new int[]{0x40FFFFFF, 0x00FFFFFF});

        android.graphics.drawable.LayerDrawable ld =
                new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{body, light});
        b.setBackground(ld);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private LinearLayout.LayoutParams matchWidth(int height) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height);
        lp.setMargins(0, dp(6), 0, dp(6));
        return lp;
    }

    /** 极细线宽（0.75dp ≈ 视觉 1-2px），用于内高光。 */
    private int hairline() {
        return Math.max(1, (int) (getResources().getDisplayMetrics().density * 0.75f));
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }

    // ================= 服务控制 =================

    private void startOllama() {
        Intent i = new Intent(this, OllamaService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        refreshStatus(OllamaService.STATE_STARTING);
    }

    private void stopOllama() {
        stopService(new Intent(this, OllamaService.class));
        refreshStatus(OllamaService.STATE_STOPPED);
    }

    private void toggleLog() {
        boolean collapsed = logPanel.getVisibility() == View.VISIBLE;
        Prefs.setLogCollapsed(this, collapsed);
        logPanel.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        logExpandBar.setVisibility(collapsed ? View.VISIBLE : View.GONE);
        logToggleBtn.setText(collapsed ? "展开" : "收起");
    }

    private void clearLog() {
        OllamaService.clearLog();
        refreshLog();
    }

    // ================= 模型拉取 / 对话 =================

    private void pullModel() {
        final String model = modelEdit.getText().toString().trim();
        if (model.isEmpty()) {
            Toast.makeText(this, "请先填写模型名", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "开始拉取 " + model + "，进度见上方日志", Toast.LENGTH_LONG).show();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String body = "{\"model\":\"" + escape(model) + "\",\"stream\":false}";
                    String resp = post("/api/pull", body);
                    final String error = extractJsonField(resp, "error");
                    final String result = (error != null && !error.isEmpty())
                            ? "拉取失败：" + error
                            : (resp != null && !resp.isEmpty() ? "拉取结果：" + resp : "（已提交，等待完成）");
                    runOnUiThread(new Runnable() {
                        @Override public void run() { appendModelBubble(null, result, null); }
                    });
                } catch (Exception e) {
                    final String msg = "拉取失败：" + e.getMessage();
                    runOnUiThread(new Runnable() {
                        @Override public void run() { appendModelBubble(null, msg, null); }
                    });
                }
            }
        }).start();
    }

    private void sendChat() {
        final String model = modelEdit.getText().toString().trim();
        final String prompt = promptEdit.getText().toString().trim();
        if (model.isEmpty() || prompt.isEmpty()) {
            Toast.makeText(this, "请填写模型名和问题", Toast.LENGTH_SHORT).show();
            return;
        }
        // 用户气泡（右侧）
        appendUserBubble(prompt);
        // 模型占位气泡
        pendingBubble = appendPlaceholder("（思考中…）");

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String opts = buildOptionsJson();
                    String body = "{\"model\":\"" + escape(model)
                            + "\",\"messages\":[{\"role\":\"user\",\"content\":\"" + escape(prompt)
                            + "\"}],\"stream\":false"
                            + (opts.isEmpty() ? "" : ",\"options\":{" + opts + "}")
                            + "}";
                    String resp = post("/api/chat", body);
                    final String reasoning = extractJsonField(resp, "reasoning_content");
                    final String content = extractJsonField(resp, "content");
                    final String error = extractJsonField(resp, "error");
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (pendingBubble != null) {
                                chatContainer.removeView(pendingBubble);
                                pendingBubble = null;
                            }
                            if (error != null && !error.isEmpty()) {
                                appendModelBubble(null, "错误：" + error, null);
                            } else if (content != null && !content.isEmpty()) {
                                appendModelBubble(reasoning, content, null);
                            } else {
                                appendModelBubble(null, "(未解析到回复) " + resp, null);
                            }
                        }
                    });
                } catch (Exception e) {
                    final String msg = "对话失败：" + e.getMessage();
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (pendingBubble != null) {
                                chatContainer.removeView(pendingBubble);
                                pendingBubble = null;
                            }
                            appendModelBubble(null, msg, null);
                        }
                    });
                }
            }
        }).start();
    }

    private String buildOptionsJson() {
        StringBuilder sb = new StringBuilder();
        for (String key : Prefs.OPTION_KEYS) {
            String v = Prefs.getStr(this, key).trim();
            if (v.isEmpty()) {
                continue;
            }
            sb.append('"').append(key).append("\":").append(v).append(',');
        }
        if (sb.length() > 0) {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    // ================= 气泡 =================

    /** 用户气泡：右侧、绿色渐变。 */
    private void appendUserBubble(String text) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(15);
        b.setTextColor(Color.WHITE);
        b.setLineSpacing(dp(2), 1f);
        b.setTextIsSelectable(true);
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable d = new GradientDrawable();
        d.setOrientation(GradientDrawable.Orientation.TL_BR);
        d.setColors(new int[]{C_GREEN_A, C_GREEN_B});
        d.setCornerRadii(new float[]{dp(18), dp(18), dp(6), dp(18), dp(18), dp(18), dp(18), dp(18)});
        d.setStroke(dp(1), 0x66FFFFFF);
        b.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END;
        lp.topMargin = dp(6);
        lp.setMarginEnd(dp(2));
        chatContainer.addView(b, lp);
    }

    /** 模型气泡：左侧白色，思考过程以粗体写在气泡顶部。 */
    private void appendModelBubble(String reasoning, String content, String error) {
        TextView b = new TextView(this);
        b.setTextSize(15);
        b.setTextColor(C_TEXT);
        b.setLineSpacing(dp(2), 1f);
        b.setTextIsSelectable(true);
        b.setPadding(dp(14), dp(10), dp(14), dp(10));

        SpannableStringBuilder ss = new SpannableStringBuilder();
        if (reasoning != null && !reasoning.isEmpty()) {
            ss.append("思考过程\n");
            ss.setSpan(new StyleSpan(Typeface.BOLD), 0, ss.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            ss.setSpan(new android.text.style.ForegroundColorSpan(0xFF9A3412),
                    0, ss.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int start = ss.length();
            ss.append(reasoning).append("\n\n");
            ss.setSpan(new StyleSpan(Typeface.BOLD), start, ss.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            ss.setSpan(new android.text.style.ForegroundColorSpan(0xFF7C2D12),
                    start, ss.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (content != null && !content.isEmpty()) {
            ss.append(content);
        }
        if (ss.length() == 0 && error != null) {
            ss.append(error);
        }
        b.setText(ss);

        GradientDrawable d = new GradientDrawable();
        d.setCornerRadii(new float[]{dp(6), dp(18), dp(18), dp(18), dp(18), dp(18), dp(18), dp(18)});
        d.setColor(0xF5FFFFFF);
        d.setStroke(dp(1), 0x8AFFFFFF);
        b.setBackground(d);
        if (Build.VERSION.SDK_INT >= 28) {
            b.setElevation(dp(2));
            b.setOutlineAmbientShadowColor(0x14000000);
            b.setOutlineSpotShadowColor(0x14000000);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.START;
        lp.topMargin = dp(6);
        lp.setMarginStart(dp(2));
        chatContainer.addView(b, lp);
    }

    /** 占位气泡（思考中…），用于非流式等待期间。 */
    private TextView appendPlaceholder(String text) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(14);
        b.setTextColor(C_TEXT_SUB);
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadii(new float[]{dp(6), dp(18), dp(18), dp(18), dp(18), dp(18), dp(18), dp(18)});
        d.setColor(0xB8FFFFFF);
        d.setStroke(dp(1), 0x66FFFFFF);
        b.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.START;
        lp.topMargin = dp(6);
        chatContainer.addView(b, lp);
        return b;
    }

    // ================= 日志 / 状态刷新 =================

    private void refreshLog() {
        final String log = OllamaService.getLog();
        runOnUiThread(new Runnable() {
            @Override public void run() {
                logView.setText(log.length() > 0 ? log : "（暂无日志）");
            }
        });
    }

    private void refreshStatus(final String state) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (OllamaService.STATE_RUNNING.equals(state)) {
                    statusDot.setTextColor(C_GREEN_A);
                    statusView.setText("服务运行中 · " + Prefs.bindAddress(MainActivity.this));
                    statusView.setTextColor(C_TEXT);
                    startBtn.setText("重 启 服 务");
                } else if (OllamaService.STATE_STARTING.equals(state)) {
                    statusDot.setTextColor(C_AMBER);
                    statusView.setText("正在启动…");
                    statusView.setTextColor(C_TEXT);
                } else if (OllamaService.STATE_ERROR.equals(state)) {
                    statusDot.setTextColor(C_RED);
                    statusView.setText("启动失败，详见上方日志");
                    statusView.setTextColor(C_RED);
                } else {
                    statusDot.setTextColor(Color.GRAY);
                    statusView.setText("服务未启动");
                    statusView.setTextColor(C_TEXT);
                    startBtn.setText("启 动 服 务");
                }
            }
        });
    }

    // ================= 权限 =================

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        }
    }

    private void requestStoragePermissionIfNeeded() {
        if (LogFile.hasPermission(this)) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            new AlertDialog.Builder(this)
                    .setTitle("需要存储权限")
                    .setMessage("为了把日志保存到 /storage/emulated/0/ollama-log/ 方便你反馈问题，\n请在接下来的页面中开启「允许访问所有文件」。")
                    .setPositiveButton("去开启", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            try {
                                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                                i.setData(Uri.parse("package:" + getPackageName()));
                                startActivity(i);
                            } catch (Exception e) {
                                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                            }
                        }
                    })
                    .setNegativeButton("暂不", null)
                    .show();
        } else {
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            LogFile.ensureDir(this);
            Toast.makeText(this,
                    LogFile.getDirPath() != null
                            ? "日志将保存至 " + LogFile.getDirPath()
                            : "未授予存储权限，日志不会保存到文件",
                    Toast.LENGTH_LONG).show();
        }
    }

    // ================= 辅助 =================

    private String post(String path, String json) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://" + Prefs.bindAddress(this) + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(1200000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            os.write(json.getBytes("UTF-8"));
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) {
                is = conn.getInputStream();
            }
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static String extractJsonField(String json, String field) {
        if (json == null) return null;
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) return null;
        int start = json.indexOf('"', colon + 1);
        if (start < 0) return null;
        int i = start + 1;
        StringBuilder out = new StringBuilder();
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(i + 1);
                if (n == 'n') { out.append('\n'); i += 2; continue; }
                if (n == 't') { out.append('\t'); i += 2; continue; }
                if (n == 'r') { out.append('\r'); i += 2; continue; }
                if (n == '"') { out.append('"'); i += 2; continue; }
                if (n == '\\') { out.append('\\'); i += 2; continue; }
            }
            if (c == '"') break;
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
