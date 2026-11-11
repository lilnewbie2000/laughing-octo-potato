package dev.kiprian.kipautomate;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class ScriptStore {
    private static final String PREF = "kipautomate_script_store";
    private static final String KEY = "scripts";
    private ScriptStore() {}

    public static String template() {
        return "[\n" +
                "  {\n" +
                "    \"name\": \"Samsung запись — тест 333\",\n" +
                "    \"enabled\": false,\n" +
                "    \"trigger\": {\"type\": \"call_active\"},\n" +
                "    \"conditions\": {\"package\": \"com.samsung.android.incallui\", \"unlocked\": true},\n" +
                "    \"actions\": [\n" +
                "      {\"type\": \"wait_text\", \"text\": \"333\", \"timeoutMs\": 12000},\n" +
                "      {\"type\": \"tap\", \"x\": 1112, \"y\": 2265},\n" +
                "      {\"type\": \"wait\", \"ms\": 700},\n" +
                "      {\"type\": \"tap\", \"x\": 500, \"y\": 1520},\n" +
                "      {\"type\": \"log\", \"message\": \"Команда записи отправлена; проверь индикатор Samsung\"}\n" +
                "    ]\n" +
                "  },\n" +
                "  {\n" +
                "    \"name\": \"Ручной тест\",\n" +
                "    \"enabled\": true,\n" +
                "    \"trigger\": {\"type\": \"manual\"},\n" +
                "    \"actions\": [\n" +
                "      {\"type\": \"log\", \"message\": \"Ручной тест успешно выполнился\"}\n" +
                "    ]\n" +
                "  }\n" +
                "]";
    }

    public static String read(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        return preferences.getString(KEY, template());
    }

    public static void write(Context context, String script) throws JSONException {
        validate(script);
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, script).apply();
    }

    public static JSONArray validate(String text) throws JSONException {
        JSONArray scripts = new JSONArray(text);
        if (scripts.length() > 50) throw new JSONException("Не более 50 сценариев");
        for (int i = 0; i < scripts.length(); i++) {
            JSONObject script = scripts.getJSONObject(i);
            if (script.optString("name").trim().isEmpty()) throw new JSONException("Нет имени сценария #" + (i + 1));
            JSONObject trigger = script.getJSONObject("trigger");
            String t = trigger.getString("type");
            if (!t.equals("manual") && !t.equals("call_active") && !t.equals("app_open"))
                throw new JSONException("Неизвестный триггер: " + t);
            if (t.equals("app_open") && trigger.optString("package").isEmpty())
                throw new JSONException("Для app_open требуется trigger.package");
            JSONArray actions = script.getJSONArray("actions");
            if (actions.length() > 100) throw new JSONException("Не более 100 действий в сценарии");
            for (int j = 0; j < actions.length(); j++) {
                JSONObject action = actions.getJSONObject(j);
                String type = action.getString("type");
                switch (type) {
                    case "wait":
                        range(action.getInt("ms"), 0, 60000, "wait.ms"); break;
                    case "tap":
                        range(action.getInt("x"), 0, 20000, "tap.x");
                        range(action.getInt("y"), 0, 20000, "tap.y"); break;
                    case "tap_text":
                    case "wait_text":
                        if (action.optString("text").isEmpty()) throw new JSONException("Не указан текст");
                        if (type.equals("wait_text")) range(action.optInt("timeoutMs", 8000), 100, 60000, "timeoutMs");
                        break;
                    case "log": if (action.optString("message").isEmpty()) throw new JSONException("Нет message"); break;
                    default: throw new JSONException("Неизвестное действие: " + type);
                }
            }
        }
        return scripts;
    }

    private static void range(int value, int min, int max, String key) throws JSONException {
        if (value < min || value > max) throw new JSONException(key + " вне диапазона " + min + "–" + max);
    }
}
