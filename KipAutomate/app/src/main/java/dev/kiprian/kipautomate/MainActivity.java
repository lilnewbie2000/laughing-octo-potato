package dev.kiprian.kipautomate;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

public class MainActivity extends Activity {
    private static final int BACKGROUND = Color.rgb(14, 19, 32);
    private static final int CARD = Color.rgb(26, 34, 51);
    private static final int TEXT = Color.rgb(240, 244, 255);
    private static final int MUTED = Color.rgb(169, 185, 207);
    private static final int ACCENT = Color.rgb(103, 164, 255);
    private static final int GREEN = Color.rgb(98, 225, 159);

    private EditText editor;
    private TextView serviceStatus;
    private TextView permissionStatus;
    private TextView scriptCount;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        setContentView(makePage());
        refreshStatus();
    }

    @Override protected void onResume() { super.onResume(); refreshStatus(); }

    private View makePage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        LinearLayout main = new LinearLayout(this);
        main.setPadding(dp(18), dp(22), dp(18), dp(24));
        main.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(main);

        TextView kicker = text("ЛОКАЛЬНАЯ АВТОМАТИЗАЦИЯ", 11, ACCENT, true);
        kicker.setLetterSpacing(.12f);
        main.addView(kicker);
        TextView title = text("KipAutomate", 30, TEXT, true);
        main.addView(title, bottom(1));
        TextView sub = text("v0.2 • звонки • TikTok • Instagram Reels", 13, MUTED, false);
        main.addView(sub, bottom(22));

        LinearLayout statusCard = card();
        serviceStatus = text("Проверка службы…", 15, TEXT, true);
        permissionStatus = text("Проверка разрешения звонков…", 12, MUTED, false);
        statusCard.addView(serviceStatus, bottom(9));
        statusCard.addView(permissionStatus, bottom(13));
        Button access = button("Открыть специальные возможности", false);
        statusCard.addView(access, bottom(7));
        access.setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
            catch (Exception e) { toast("Невозможно открыть настройки: " + e.getMessage()); }
        });
        Button phone = button("Разрешить обнаружение звонков", false);
        statusCard.addView(phone);
        phone.setOnClickListener(v -> {
            if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE}, 19);
            else toast("Разрешение уже предоставлено");
        });
        main.addView(statusCard, bottom(18));

        TextView heading = text("Сценарии", 21, TEXT, true);
        main.addView(heading, bottom(4));
        TextView desc = text("Вставь сценарий от ChatGPT или измени встроенный пример. Автоматические сценарии запускаются только при enabled: true.", 13, MUTED, false);
        main.addView(desc, bottom(12));

        editor = new EditText(this);
        editor.setText(ScriptStore.read(this));
        editor.setTextColor(TEXT);
        editor.setHintTextColor(MUTED);
        editor.setTextSize(12.5f);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setSingleLine(false);
        editor.setHorizontallyScrolling(false);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setMinLines(16);
        editor.setBackground(round(CARD, 16, Color.rgb(60, 72, 92)));
        editor.setPadding(dp(13), dp(13), dp(13), dp(13));
        main.addView(editor, bottom(10));

        scriptCount = text("", 12, MUTED, false);
        main.addView(scriptCount, bottom(12));

        LinearLayout actions1 = horizontal();
        Button validate = button("Проверить JSON", false);
        Button save = button("Сохранить", true);
        actions1.addView(validate, weightedButton());
        actions1.addView(space(8));
        actions1.addView(save, weightedButton());
        main.addView(actions1, bottom(8));
        validate.setOnClickListener(v -> validateEditor(false));
        save.setOnClickListener(v -> validateEditor(true));

        LinearLayout actions2 = horizontal();
        Button manual = button("▶ Ручной запуск", false);
        Button stop = button("■ Остановить", false);
        actions2.addView(manual, weightedButton());
        actions2.addView(space(8));
        actions2.addView(stop, weightedButton());
        main.addView(actions2, bottom(12));
        manual.setOnClickListener(v -> {
            if (!validateEditor(true)) return;
            AutomationService srv = AutomationService.instance();
            if (srv == null) { toast("Сначала включи службу KipAutomate в специальных возможностях"); return; }
            srv.runManual();
            toast("Ручной сценарий запущен. Результат — в журнале.");
        });
        stop.setOnClickListener(v -> {
            AutomationService srv = AutomationService.instance();
            if (srv != null) srv.stop("Остановлено пользователем");
            toast("Остановка отправлена");
        });

        LinearLayout actions3 = horizontal();
        Button example = button("Шаблон Samsung", false);
        Button copy = button("Копировать скрипт", false);
        actions3.addView(example, weightedButton());
        actions3.addView(space(8));
        actions3.addView(copy, weightedButton());
        main.addView(actions3, bottom(20));
        example.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Вернуть примеры?")
                .setMessage("Несохранённые изменения в редакторе будут заменены. При необходимости скопируй их перед сбросом.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Заменить", (dialog, which) -> editor.setText(ScriptStore.template()))
                .show());
        copy.setOnClickListener(v -> copyText(editor.getText().toString(), "Сценарий скопирован"));

        TextView socialHeading = text("Фильтры TikTok / Instagram", 21, TEXT, true);
        main.addView(socialHeading, bottom(5));
        TextView socialHelp = text("Добавляет два фоновых сценария, не затрагивая существующую запись Samsung. Работают, пока открыто нужное приложение.", 13, MUTED, false);
        main.addView(socialHelp, bottom(10));
        Button addFilters = button("Добавить фильтры TikTok + Reels", true);
        main.addView(addFilters, bottom(8));
        addFilters.setOnClickListener(v -> addSocialFilters());
        Button pauseFilters = button("Пауза / включить фильтры", false);
        main.addView(pauseFilters, bottom(16));
        pauseFilters.setOnClickListener(v -> {
            AutomationService s = AutomationService.instance();
            if (s == null) { toast("Сначала включи Accessibility"); return; }
            toast(s.toggleSocialPaused() ? "Фильтры приостановлены" : "Фильтры включены");
        });
        TextView logsHead = text("Диагностика", 21, TEXT, true);
        main.addView(logsHead, bottom(6));
        TextView logsDesc = text("Каждый шаг пишет START / COMPLETED / CANCELLED или конкретную ошибку. Ни один жест не исчезает молча при импорте: сценарии — обычный текст.", 13, MUTED, false);
        main.addView(logsDesc, bottom(12));
        LinearLayout actions4 = horizontal();
        Button logs = button("Открыть журнал", true);
        Button clear = button("Очистить журнал", false);
        actions4.addView(logs, weightedButton());
        actions4.addView(space(8));
        actions4.addView(clear, weightedButton());
        main.addView(actions4, bottom(15));
        logs.setOnClickListener(v -> showLogs());
        clear.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Очистить журнал?")
                .setNegativeButton("Отмена", null).setPositiveButton("Очистить", (d,w) -> {LogBook.clear(this); toast("Журнал очищен");}).show());

        TextView safety = text("Важно: доступ к экрану включает чтение элементов открытых приложений. Автоматическая запись звонков может регулироваться законом; получай согласие участников. При блокировке экрана жесты обычно не работают. Версия 0.2 — экспериментальная.", 12, MUTED, false);
        main.addView(safety);
        return scroll;
    }

    private void addSocialFilters() {
        try {
            JSONArray existing=ScriptStore.validate(editor.getText().toString());
            JSONArray extras=ScriptStore.socialTemplates();
            int added=0;
            for (int i=0;i<extras.length();i++) {
                JSONObject extra=extras.getJSONObject(i);
                String packageName=extra.getJSONObject("trigger").getString("package");
                boolean found=false;
                for (int j=0;j<existing.length();j++) {
                    JSONObject old=existing.getJSONObject(j);
                    JSONObject trigger=old.optJSONObject("trigger");
                    if (trigger!=null && "monitor".equals(trigger.optString("type")) &&
                         packageName.equals(trigger.optString("package"))) { found=true;break; }
                }
                if(!found) { existing.put(extra);added++; }
            }
            editor.setText(existing.toString(2));
            if(validateEditor(true)) toast(added==0?"Фильтры уже добавлены":"Добавлено фильтров: "+added);
        } catch (Exception e) {toast("Ошибка установки фильтров: "+e.getMessage());}
    }

    private boolean validateEditor(boolean save) {
        String input = editor.getText().toString();
        try {
            JSONArray scripts = ScriptStore.validate(input);
            if (save) {
                ScriptStore.write(this, input);
                LogBook.write(this, "SCRIPTS SAVED — " + scripts.length() + " сценариев");
                AutomationService s = AutomationService.instance();
                if (s != null) s.reloadSocialFilters();
            }
            scriptCount.setText("✓ Синтаксис верный · сценариев: " + scripts.length());
            scriptCount.setTextColor(GREEN);
            toast(save ? "Сохранено: " + scripts.length() + " сценариев" : "JSON прошёл проверку");
            return true;
        } catch (Exception e) {
            scriptCount.setText("Ошибка: " + e.getMessage());
            scriptCount.setTextColor(Color.rgb(255, 143, 143));
            toast("Проверь сценарий: " + e.getMessage());
            return false;
        }
    }

    private void refreshStatus() {
        if (serviceStatus == null) return;
        boolean active = AutomationService.isRunning();
        serviceStatus.setText(active ? "● Accessibility: подключена" : "○ Accessibility: выключена");
        serviceStatus.setTextColor(active ? GREEN : Color.rgb(255, 199, 125));
        boolean phone = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
        permissionStatus.setText(phone ? "Разрешение телефонных событий: есть" : "Разрешение телефонных событий: требуется");
        try {
            JSONArray arr = ScriptStore.validate(editor.getText().toString());
            scriptCount.setText("Сценариев в редакторе: " + arr.length() + " · нажми «Сохранить» после изменений");
        } catch (Exception ignored) { scriptCount.setText("Сценарий ещё не проверен"); }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshStatus();
    }

    private void showLogs() {
        String data = LogBook.read(this);
        EditText input = new EditText(this);
        input.setText(data);
        input.setTextIsSelectable(true);
        input.setTextSize(12);
        input.setTypeface(Typeface.MONOSPACE);
        input.setPadding(dp(12), dp(14), dp(12), dp(14));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("Журнал KipAutomate")
                .setView(scroll)
                .setNegativeButton("Закрыть", null)
                .setPositiveButton("Копировать", (d, w) -> copyText(data, "Журнал скопирован"))
                .show();
    }

    private void copyText(String text, String notice) {
        ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cb != null) cb.setPrimaryClip(ClipData.newPlainText("KipAutomate", text));
        toast(notice);
    }
    private void toast(String t) { Toast.makeText(this, t, Toast.LENGTH_LONG).show(); }
    private int dp(float d) { return Math.round(d * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    private GradientDrawable round(int color, int radiusDp, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(radiusDp));
        if (stroke != Color.TRANSPARENT) d.setStroke(dp(1), stroke);
        return d;
    }
    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(17), dp(16), dp(17));
        card.setBackground(round(CARD, 16, Color.rgb(54, 67, 88)));
        return card;
    }
    private Button button(String name, boolean primary) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(name);
        b.setTextSize(12);
        b.setTextColor(primary ? BACKGROUND : TEXT);
        b.setBackground(round(primary ? ACCENT : Color.rgb(51, 65, 88), 12, Color.TRANSPARENT));
        b.setPadding(dp(9), dp(5), dp(9), dp(5));
        b.setMinHeight(dp(48));
        return b;
    }
    private LinearLayout horizontal() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private View space(int wDp) { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(dp(wDp), dp(1))); return v; }
    private LinearLayout.LayoutParams weightedButton() { return new LinearLayout.LayoutParams(0, dp(49), 1f); }
    private LinearLayout.LayoutParams bottom(int d) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); p.bottomMargin = dp(d); return p; }
}
