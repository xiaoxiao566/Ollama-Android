package com.ollama.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置页：
 *  - GPU 后端（CPU / Vulkan / OpenCL）
 *  - 「变量」：基础参数（并发、端口、上下文、采样等，可放心调整）
 *  - 「高级」：与模型架构/内存强相关的参数，点开前弹警告，
 *    设置不当可能导致模型无法正常加载或运行。
 * 所有数字参数留空 = 不设置（使用 ollama 默认值）。
 */
public class SettingsActivity extends Activity {

    // ---- 配色（与主界面同一套液态玻璃风格） ----
    private static final int C_BG_TOP = 0xFFF2F6FC;
    private static final int C_BG_BOTTOM = 0xFFDDE7F3;
    private static final int C_GREEN_A = 0xFF3ED67E;
    private static final int C_GREEN_B = 0xFF1CA85A;
    private static final int C_GLASS = 0xE8FFFFFF;   // 玻璃卡底色（半透明白）
    private static final int C_GLASS_STROKE = 0x88FFFFFF; // 高光描边
    private static final int C_TEXT = 0xFF1F2937;
    private static final int C_TEXT_SUB = 0xFF6B7280;
    private static final int C_WARN = 0xFFB45309;
    private static final int C_WARN_BG = 0xFFFFF7E6;

    /** 参数定义：键 / 显示名 / 说明。 */
    private static class Param {
        final String key;
        final String label;
        final String hint;

        Param(String key, String label, String hint) {
            this.key = key;
            this.label = label;
            this.hint = hint;
        }
    }

    // 「变量」：基础参数（不算特别重要，可放心调整）
    private static final Param[] BASIC_PARAMS = {
            new Param("OLLAMA_NUM_PARALLEL", "OLLAMA_NUM_PARALLEL", "并发请求数（同时处理几个对话/请求）"),
            new Param("OLLAMA_MAX_LOADED_MODELS", "OLLAMA_MAX_LOADED_MODELS", "同时加载的模型数上限（内存大户）"),
            new Param("OLLAMA_MAX_QUEUE", "OLLAMA_MAX_QUEUE", "请求排队上限"),
            new Param("port", "OLLAMA_PORT（服务端口）", "默认 11434，改动后访问地址同步变化"),
            new Param("OLLAMA_NUM_THREADS", "OLLAMA_NUM_THREADS", "服务端计算线程数"),
            new Param("num_ctx", "num_ctx", "上下文长度（token），默认 4096"),
            new Param("num_predict", "num_predict", "单次最多生成 token 数，-1 不限制"),
            new Param("num_gpu", "num_gpu", "GPU 层数，-1 自动分配"),
            new Param("num_thread", "num_thread", "推理线程数，0 自动"),
            new Param("temperature", "temperature", "温度，越高越随机（0.8 常用）"),
            new Param("top_p", "top_p", "核采样，1 关闭"),
    };

    // 「高级」：可能导致模型无法正常跑起来的参数
    private static final Param[] ADVANCED_PARAMS = {
            new Param("OLLAMA_CONTEXT_LENGTH", "OLLAMA_CONTEXT_LENGTH", "全局默认上下文长度，过大会内存/显存不足"),
            new Param("OLLAMA_MAX_TRANSFER_STREAMS", "OLLAMA_MAX_TRANSFER_STREAMS", "模型传输并发流数"),
            new Param("seed", "seed", "随机种子，固定后结果可复现"),
            new Param("top_k", "top_k", "采样候选数（默认 40）"),
            new Param("repeat_last_n", "repeat_last_n", "重复惩罚窗口（默认 64）"),
            new Param("mirostat", "mirostat", "Mirostat 采样模式 0/1/2，仅新模型支持"),
            new Param("num_batch", "num_batch", "批大小，过大会吃内存（默认 2048）"),
            new Param("num_gqa", "num_gqa", "GQA 层数，与模型架构绑定，填错模型无法加载"),
            new Param("repeat_penalty", "repeat_penalty", "重复惩罚系数（默认 1.1）"),
            new Param("mirostat_tau", "mirostat_tau", "Mirostat 目标困惑度"),
            new Param("mirostat_eta", "mirostat_eta", "Mirostat 学习率"),
            new Param("tfs_z", "tfs_z", "尾部频率采样（默认 1 关闭）"),
    };

    private RadioButton rCpu, rVulkan, rOpencl;
    private LinearLayout advancedBody;
    private boolean advancedExpanded = false;
    private final List<EditText> allFields = new ArrayList<EditText>();
    private final List<Param> allParams = new ArrayList<Param>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        for (Param p : BASIC_PARAMS) allParams.add(p);
        for (Param p : ADVANCED_PARAMS) allParams.add(p);
        setContentView(buildUi());
        loadPrefs();
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{C_BG_TOP, C_BG_BOTTOM}));

        // 背景柔光（与主界面一致，让玻璃透出颜色）
        FrameLayout.LayoutParams lp1 = new FrameLayout.LayoutParams(dp(240), dp(240));
        lp1.gravity = Gravity.TOP | Gravity.END;
        lp1.topMargin = -dp(90);
        lp1.rightMargin = -dp(60);
        root.addView(orb(new int[]{0x40B9E8C8, 0x00B9E8C8}, dp(120)), lp1);

        FrameLayout.LayoutParams lp2 = new FrameLayout.LayoutParams(dp(180), dp(180));
        lp2.gravity = Gravity.BOTTOM | Gravity.START;
        lp2.bottomMargin = -dp(70);
        lp2.leftMargin = -dp(50);
        root.addView(orb(new int[]{0x3394B9FF, 0x0094B9FF}, dp(90)), lp2);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        col.addView(buildHeader());

        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(4), dp(16), dp(20));

        body.addView(buildGpuCard());
        body.addView(buildBasicCard());
        body.addView(buildAdvancedCard());
        body.addView(buildRunOptionsCard());
        body.addView(buildSaveButton());

        scroll.addView(body, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    /** 液态玻璃标题栏：磨砂返回药丸 + 标题（与主界面同风格）。 */
    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(16), dp(16), dp(10));

        TextView back = new TextView(this);
        back.setText("\u2039 返回");
        back.setTextSize(15);
        back.setTypeface(null, Typeface.BOLD);
        back.setTextColor(C_TEXT);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(14), dp(7), dp(14), dp(7));
        back.setBackground(frostPill());
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        header.addView(back);

        TextView title = new TextView(this);
        title.setText("设置");
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(C_TEXT);
        title.setPadding(dp(14), 0, 0, 0);
        header.addView(title);
        return header;
    }

    // ================= GPU 后端 =================

    private View buildGpuCard() {
        LinearLayout card = card();
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setOrientation(LinearLayout.VERTICAL);

        card.addView(sectionLabel("GPU 后端"));

        RadioGroup rg = new RadioGroup(this);
        rg.setOrientation(LinearLayout.VERTICAL);

        rCpu = radio("CPU  ——  最稳定，所有机型通用");
        rVulkan = radio("Vulkan  ——  推荐，走系统 GPU 驱动加速");
        rOpencl = radio("OpenCL  ——  实验性（多数手机无驱动，会回退 CPU）");

        rg.addView(rCpu);
        rg.addView(rVulkan);
        rg.addView(rOpencl);
        card.addView(rg);
        return card;
    }

    private RadioButton radio(String text) {
        RadioButton rb = new RadioButton(this);
        rb.setText(text);
        rb.setTextSize(15);
        rb.setTextColor(C_TEXT);
        return rb;
    }

    // ================= 「变量」基础参数 =================

    private View buildBasicCard() {
        LinearLayout card = card();
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setOrientation(LinearLayout.VERTICAL);

        card.addView(sectionLabel("变量 · 基础参数"));

        TextView sub = new TextView(this);
        sub.setText("常用调整项，留空 = 使用 ollama 默认值");
        sub.setTextSize(12);
        sub.setTextColor(C_TEXT_SUB);
        sub.setPadding(0, dp(2), 0, dp(8));
        card.addView(sub);

        for (Param p : BASIC_PARAMS) {
            card.addView(paramField(p));
        }
        return card;
    }

    // ================= 「高级」参数（警告后展开） =================

    private View buildAdvancedCard() {
        final LinearLayout card = card();
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setOrientation(LinearLayout.VERTICAL);

        // 警告式 header（始终可见，点击展开/收起）
        final LinearLayout advHeader = new LinearLayout(this);
        advHeader.setOrientation(LinearLayout.HORIZONTAL);
        advHeader.setGravity(Gravity.CENTER_VERTICAL);
        advHeader.setBackground(round(C_WARN_BG, dp(12)));
        advHeader.setPadding(dp(12), dp(10), dp(12), dp(10));

        final TextView advTitle = new TextView(this);
        advTitle.setText("\u26A0 高级参数（可能导致模型无法正常跑起来）");
        advTitle.setTextSize(14);
        advTitle.setTypeface(null, Typeface.BOLD);
        advTitle.setTextColor(C_WARN);
        advTitle.setPadding(0, 0, dp(8), 0);
        advHeader.addView(advTitle, new LinearLayout.LayoutParams(0, -2, 1f));

        final TextView advArrow = new TextView(this);
        advArrow.setText("\u25BC");
        advArrow.setTextSize(14);
        advArrow.setTextColor(C_WARN);
        advHeader.addView(advArrow);

        advHeader.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (advancedExpanded) {
                    collapseAdvanced(advArrow);
                } else {
                    expandAdvanced(advArrow);
                }
            }
        });
        card.addView(advHeader);

        // 字段容器（默认隐藏，点开高级并确认警告后显示）
        advancedBody = new LinearLayout(this);
        advancedBody.setOrientation(LinearLayout.VERTICAL);
        advancedBody.setVisibility(View.GONE);
        for (Param p : ADVANCED_PARAMS) {
            advancedBody.addView(paramField(p));
        }
        card.addView(advancedBody);

        return card;
    }

    /** 点开高级：先弹警告，确认后再展开字段。 */
    private void expandAdvanced(final TextView arrow) {
        new AlertDialog.Builder(this)
                .setTitle("\u26A0 高级参数警告")
                .setMessage("以下参数与模型架构、内存占用直接相关，设置不当可能导致模型无法正常加载或运行。\n\n一般保持默认（留空）即可。\n\n确定要继续修改吗？")
                .setNegativeButton("暂不修改", null)
                .setPositiveButton("继续修改", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        advancedExpanded = true;
                        advancedBody.setVisibility(View.VISIBLE);
                        arrow.setText("\u25B2");
                    }
                })
                .show();
    }

    private void collapseAdvanced(TextView arrow) {
        advancedExpanded = false;
        advancedBody.setVisibility(View.GONE);
        arrow.setText("\u25BC");
    }

    // ================= 「运行选项」：ollama run 后缀 =================

    private Switch swVerbose, swNoWordWrap, swInsecure, swThink, swHideThinking,
            swExperimental, swWebSearch;
    private EditText keepAliveEdit, systemEdit;

    private View buildRunOptionsCard() {
        LinearLayout card = card();
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setOrientation(LinearLayout.VERTICAL);

        card.addView(sectionLabel("模型运行选项"));

        TextView sub = new TextView(this);
        sub.setText("对应 ollama run 的命令行后缀，扩展模型使用方式");
        sub.setTextSize(12);
        sub.setTextColor(C_TEXT_SUB);
        sub.setPadding(0, dp(2), 0, dp(6));
        card.addView(sub);

        swVerbose = new Switch(this);
        card.addView(switchRow("显示推理速度",
                "--verbose：对话末尾显示 tokens/s 速度统计", swVerbose));
        swNoWordWrap = new Switch(this);
        card.addView(switchRow("不自动换行",
                "--nowordwrap：输出不折行，横向滚动查看原文", swNoWordWrap));
        swInsecure = new Switch(this);
        card.addView(switchRow("允许不安全连接",
                "--insecure：拉取模型时不校验证书", swInsecure));
        swThink = new Switch(this);
        card.addView(switchRow("显示思考过程",
                "--think：展示模型的思考内容", swThink));
        swHideThinking = new Switch(this);
        card.addView(switchRow("隐藏思考过程",
                "--hidethinking：不展示思考内容（优先于“显示思考过程”）", swHideThinking));
        swExperimental = new Switch(this);
        card.addView(switchRow("启用实验特性",
                "--experimental：启用 ollama 实验性功能", swExperimental));
        swWebSearch = new Switch(this);
        card.addView(switchRow("实验性网络搜索",
                "--experimental-websearch：启用实验性联网搜索", swWebSearch));

        // --keepalive（模型驻留时长）
        LinearLayout kaWrap = new LinearLayout(this);
        kaWrap.setOrientation(LinearLayout.VERTICAL);
        kaWrap.setPadding(0, dp(8), 0, dp(2));
        TextView kaLabel = new TextView(this);
        kaLabel.setText("--keepalive（模型驻留时长）");
        kaLabel.setTextSize(13);
        kaLabel.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        kaLabel.setTextColor(C_TEXT);
        kaWrap.addView(kaLabel);
        keepAliveEdit = new EditText(this);
        keepAliveEdit.setHint("如 5m / 30m / -1（永久驻留）/ 0（用完即卸）");
        keepAliveEdit.setSingleLine(true);
        keepAliveEdit.setTextSize(15);
        keepAliveEdit.setTextColor(C_TEXT);
        keepAliveEdit.setHintTextColor(C_TEXT_SUB);
        keepAliveEdit.setPadding(dp(12), dp(10), dp(12), dp(10));
        keepAliveEdit.setBackground(glassRound(dp(16), 0xAAFFFFFF, 0x66FFFFFF));
        keepAliveEdit.setTag(Prefs.KEY_KEEP_ALIVE);
        kaWrap.addView(keepAliveEdit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(kaWrap);
        allFields.add(keepAliveEdit);

        // --system（自定义系统提示词）
        LinearLayout sysWrap = new LinearLayout(this);
        sysWrap.setOrientation(LinearLayout.VERTICAL);
        sysWrap.setPadding(0, dp(6), 0, dp(2));
        TextView sysLabel = new TextView(this);
        sysLabel.setText("--system（自定义系统提示词）");
        sysLabel.setTextSize(13);
        sysLabel.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        sysLabel.setTextColor(C_TEXT);
        sysWrap.addView(sysLabel);
        systemEdit = new EditText(this);
        systemEdit.setHint("例如：你是一个友好的助手，用中文回答");
        systemEdit.setGravity(Gravity.TOP);
        systemEdit.setMinLines(3);
        systemEdit.setTextSize(15);
        systemEdit.setTextColor(C_TEXT);
        systemEdit.setHintTextColor(C_TEXT_SUB);
        systemEdit.setPadding(dp(12), dp(10), dp(12), dp(10));
        systemEdit.setBackground(glassRound(dp(16), 0xAAFFFFFF, 0x66FFFFFF));
        systemEdit.setTag(Prefs.KEY_SYSTEM_PROMPT);
        sysWrap.addView(systemEdit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(sysWrap);
        allFields.add(systemEdit);

        return card;
    }

    /** 一个「文字说明 + Switch」行。 */
    private LinearLayout switchRow(String title, String desc, Switch sw) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        LinearLayout textWrap = new LinearLayout(this);
        textWrap.setOrientation(LinearLayout.VERTICAL);
        textWrap.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14);
        t.setTypeface(null, Typeface.BOLD);
        t.setTextColor(C_TEXT);
        textWrap.addView(t);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(11);
        d.setTextColor(C_TEXT_SUB);
        d.setPadding(0, dp(1), 0, 0);
        textWrap.addView(d);

        row.addView(textWrap);
        row.addView(sw);
        return row;
    }

    // ================= 字段 / 保存 =================

    private View paramField(Param p) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, dp(6), 0, dp(2));

        TextView label = new TextView(this);
        label.setText(p.label);
        label.setTextSize(13);
        label.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        label.setTextColor(C_TEXT);
        wrap.addView(label);

        EditText e = new EditText(this);
        e.setHint(p.hint);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_SIGNED
                | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setTextSize(15);
        e.setTextColor(C_TEXT);
        e.setHintTextColor(C_TEXT_SUB);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setBackground(glassRound(dp(16), 0xAAFFFFFF, 0x66FFFFFF));
        e.setTag(p.key);
        wrap.addView(e, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        allFields.add(e);
        return wrap;
    }

    private View buildSaveButton() {
        Button save = new Button(this);
        save.setText("保存设置");
        save.setAllCaps(false);
        save.setTextSize(16);
        save.setTypeface(null, Typeface.BOLD);
        save.setTextColor(Color.WHITE);

        GradientDrawable body = new GradientDrawable();
        body.setCornerRadius(dp(26));
        body.setOrientation(GradientDrawable.Orientation.TL_BR);
        body.setColors(new int[]{0xFF3ED67E, 0xFF1CA85A});
        body.setStroke(hairline(), 0xB3FFFFFF);   // 细内描边

        // 左上受光（玻璃光泽）
        GradientDrawable light = new GradientDrawable();
        light.setCornerRadius(dp(26));
        light.setOrientation(GradientDrawable.Orientation.TL_BR);
        light.setColors(new int[]{0x33FFFFFF, 0x00FFFFFF});

        android.graphics.drawable.LayerDrawable ld =
                new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{body, light});
        save.setBackground(ld);
        save.setPadding(dp(8), dp(14), dp(8), dp(14));
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(12), 0, 0);
        save.setLayoutParams(lp);
        return save;
    }

    // ================= 样式工具 =================

    /** 液态玻璃卡片：半透明白底 + 高光描边 + 悬浮阴影（与主界面一致）。 */
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(glassRound(dp(20), C_GLASS, C_GLASS_STROKE));
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

    /** 分区标题：绿色小圆点 + 加粗文字（与主界面同款）。 */
    private View sectionLabel(String text) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        wrap.setPadding(0, 0, 0, dp(8));

        View dot = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColors(new int[]{C_GREEN_A, C_GREEN_B});
        dot.setBackground(d);
        wrap.addView(dot, new LinearLayout.LayoutParams(dp(6), dp(6)));

        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTypeface(null, Typeface.BOLD);
        t.setTextColor(C_TEXT);
        t.setPadding(dp(6), 0, 0, 0);
        wrap.addView(t);
        return wrap;
    }

    /** 磨砂药丸：半透明白底 + 高光描边（返回按钮用）。 */
    private GradientDrawable frostPill() {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(20));
        d.setColor(0xD9FFFFFF);
        d.setStroke(hairline(), 0xAAFFFFFF);
        return d;
    }

    /** 玻璃圆角底：半透明白 + 可选描边。 */
    private GradientDrawable glassRound(int radius, int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(radius);
        d.setColor(fill);
        if (stroke != 0) {
            d.setStroke(hairline(), stroke);
        }
        return d;
    }

    /** 背景柔光圆块（径向渐变压暗的边缘）。 */
    private View orb(int[] colors, int radius) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setGradientType(android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(radius);
        g.setGradientCenter(0.5f, 0.5f);
        g.setColors(colors);
        View v = new View(this);
        v.setBackground(g);
        return v;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    /** 极细线宽（0.75dp ≈ 视觉 1-2px），用于内高光。 */
    private int hairline() {
        return Math.max(1, (int) (getResources().getDisplayMetrics().density * 0.75f));
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }

    // ================= 读取 / 保存 =================

    private void loadPrefs() {
        String gpu = Prefs.gpuBackend(this);
        if (Prefs.GPU_VULKAN.equals(gpu)) rVulkan.setChecked(true);
        else if (Prefs.GPU_OPENCL.equals(gpu)) rOpencl.setChecked(true);
        else rCpu.setChecked(true);

        swVerbose.setChecked(Prefs.verbose(this));
        swNoWordWrap.setChecked(Prefs.noWordWrap(this));
        swInsecure.setChecked(Prefs.insecure(this));
        swThink.setChecked(Prefs.think(this));
        swHideThinking.setChecked(Prefs.hideThinking(this));
        swExperimental.setChecked(Prefs.experimental(this));
        swWebSearch.setChecked(Prefs.experimentalWebsearch(this));

        for (EditText e : allFields) {
            String key = (String) e.getTag();
            e.setText(Prefs.getStr(this, key));
        }
    }

    private void save() {
        String gpu = Prefs.GPU_CPU;
        if (rVulkan.isChecked()) gpu = Prefs.GPU_VULKAN;
        else if (rOpencl.isChecked()) gpu = Prefs.GPU_OPENCL;
        Prefs.setGpuBackend(this, gpu);

        Prefs.setVerbose(this, swVerbose.isChecked());
        Prefs.setNoWordWrap(this, swNoWordWrap.isChecked());
        Prefs.setInsecure(this, swInsecure.isChecked());
        Prefs.setThink(this, swThink.isChecked());
        Prefs.setHideThinking(this, swHideThinking.isChecked());
        Prefs.setExperimental(this, swExperimental.isChecked());
        Prefs.setExperimentalWebsearch(this, swWebSearch.isChecked());

        for (EditText e : allFields) {
            String key = (String) e.getTag();
            Prefs.setStr(this, key, e.getText().toString());
        }

        Toast.makeText(this, "设置已保存，重启服务后生效", Toast.LENGTH_SHORT).show();
        finish();
    }
}
