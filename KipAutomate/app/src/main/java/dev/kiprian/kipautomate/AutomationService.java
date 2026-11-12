package dev.kiprian.kipautomate;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.app.KeyguardManager;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;
import java.util.Locale;

public class AutomationService extends AccessibilityService {
    private static AutomationService current;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String foreground = "";
    private String lastPhoneState = TelephonyManager.EXTRA_STATE_IDLE;
    private String lastAppOpened = "";
    private long lastAppOpenMs = 0L;
    private ScriptRun running;
    private BroadcastReceiver phoneReceiver;
    private boolean pendingPhoneUi=false;
    private long phonePendingStart=0;
    private long phoneVisibleSince=0;
    private SocialFilter socialFilter;

    public static boolean isRunning() { return current != null; }
    public static AutomationService instance() { return current; }

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        current = this;
        socialFilter = new SocialFilter(this, handler);
        LogBook.write(this, "SERVICE START — служба Accessibility подключена");
        phoneReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) return;
                String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
                if (state == null || state.equals(lastPhoneState)) return;
                lastPhoneState = state;
                if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {
                    LogBook.write(AutomationService.this, "PHONE OFFHOOK — вызов поднят/набирается (Android не отличает набор от ответа)");
                    pendingPhoneUi=true;
                    phonePendingStart=android.os.SystemClock.uptimeMillis();
                    phoneVisibleSince=0;
                    handler.postDelayed(AutomationService.this::tryPendingPhoneUi, 280);
                } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
                    pendingPhoneUi=false;
                    phoneVisibleSince=0;
                    LogBook.write(AutomationService.this, "PHONE IDLE — вызов завершился");
                    if (running != null && "call_active".equals(running.trigger)) stop("Вызов завершён");
                }
            }
        };
        IntentFilter filter = new IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(phoneReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(phoneReceiver, filter);
            }
            LogBook.write(this, "PHONE listener registered; READ_PHONE_STATE=" +
                    (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED));
        } catch (Exception e) { LogBook.write(this, "PHONE listener ERROR: " + e.getMessage()); }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();
        foreground = pkg;
        if (pendingPhoneUi && "com.samsung.android.incallui".equals(pkg)) {
            handler.postDelayed(this::tryPendingPhoneUi, 80);
        }
        if (socialFilter != null) socialFilter.onEvent(event);
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && !pkg.equals(lastAppOpened)) {
            long now = System.currentTimeMillis();
            if (now - lastAppOpenMs >= 500 || !pkg.equals(lastAppOpened)) {
                lastAppOpened = pkg;
                lastAppOpenMs = now;
                fire("app_open", pkg);
            }
        }
    }

    @Override public void onInterrupt() { LogBook.write(this, "SERVICE INTERRUPTED"); }
    @Override public void onDestroy() {
        pendingPhoneUi=false;
        stop("Служба отключена");
        if (socialFilter != null) socialFilter.shutdown();
        current = null;
        if (phoneReceiver != null) try { unregisterReceiver(phoneReceiver); } catch (Exception ignored) {}
        LogBook.write(this, "SERVICE STOP");
        super.onDestroy();
    }

    public void runManual() { fire("manual", ""); }
    public void reloadSocialFilters() { if (socialFilter != null) socialFilter.reload(); }
    public boolean toggleSocialPaused() {if (socialFilter != null) {socialFilter.togglePaused();return socialFilter.isPaused();}return false;}
    public boolean socialPaused() {return socialFilter!=null && socialFilter.isPaused();}
    public void stop(String reason) {
        ScriptRun prev = running;
        if (prev != null) {
            running = null;
            LogBook.write(this, "STOP [" + prev.name + "]: " + reason);
        }
    }

    private void fire(String kind, String requestedPackage) {
        if (running != null) {
            LogBook.write(this, "SKIP trigger=" + kind + " — сценарий уже выполняется: " + running.name);
            return;
        }
        try {
            JSONArray arr = ScriptStore.validate(ScriptStore.read(this));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject script = arr.getJSONObject(i);
                if (!script.optBoolean("enabled", false)) continue;
                JSONObject trigger = script.getJSONObject("trigger");
                if (!kind.equals(trigger.optString("type"))) continue;
                if ("app_open".equals(kind) && !requestedPackage.equals(trigger.optString("package"))) continue;
                JSONObject cond = script.optJSONObject("conditions");
                if (cond != null) {
                    if (cond.optBoolean("unlocked", false) && isScreenLocked()) {
                        LogBook.write(this, "SKIP " + script.optString("name") + " — экран заблокирован");
                        continue;
                    }
                    String target = cond.optString("package", "");
                    if (!target.isEmpty() && !target.equals(foreground)) {
                        LogBook.write(this, "SKIP " + script.optString("name") + " — открыт " + foreground + ", нужен " + target);
                        continue;
                    }
                }
                ScriptRun run = new ScriptRun(script, kind);
                running = run;
                LogBook.write(this, "RUN " + run.name + " trigger=" + kind + " foreground=" + foreground);
                step(run);
                return;
            }
        } catch (Exception e) {
            LogBook.write(this, "TRIGGER ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Wait for Samsung in-call UI. OFFHOOK alone can mean that the number is still dialing. */
    private void tryPendingPhoneUi() {
        if (!pendingPhoneUi) return;
        if ("com.samsung.android.incallui".equals(foreground)) {
            long now=android.os.SystemClock.uptimeMillis();
            if(phoneVisibleSince==0)phoneVisibleSince=now;
            boolean duration=hasConnectedCallTimer(getRootInActiveWindow(),0);
            if(!duration && now-phoneVisibleSince<1300) {
                handler.postDelayed(this::tryPendingPhoneUi,180);
                return;
            }
            pendingPhoneUi=false;
            LogBook.write(this,"PHONE UI READY timer="+duration+" settledMs="+(now-phoneVisibleSince));
            fire("call_active", "");
        } else if (android.os.SystemClock.uptimeMillis()-phonePendingStart < 14000) {
            handler.postDelayed(this::tryPendingPhoneUi, 300);
        } else {
            pendingPhoneUi=false;
            LogBook.write(this, "PHONE UI TIMEOUT — экран вызова не появился");
        }
    }

    // Avoid starting the recording action while OFFHOOK still means dialling.
    private boolean hasConnectedCallTimer(AccessibilityNodeInfo n,int depth) {
        if(n==null || depth>30)return false;
        CharSequence cs=n.getText();
        if(cs!=null && cs.toString().trim().matches("(?:[0-9]{1,2}:)?[0-9]{2}:[0-9]{2}")) {
            Rect b=new Rect();n.getBoundsInScreen(b);
            int h=getResources().getDisplayMetrics().heightPixels;
            if(b.centerY()>h*.025f && b.centerY()<h*.24f)return true;
        }
        for(int i=0;i<n.getChildCount();i++)if(hasConnectedCallTimer(n.getChild(i),depth+1))return true;
        return false;
    }

    private boolean isScreenLocked() {
        KeyguardManager kg = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        return kg != null && kg.isKeyguardLocked();
    }

    private void step(ScriptRun run) {
        if (running != run) return;
        try {
            if (run.index >= run.actions.length()) {
                LogBook.write(this, "FINISHED " + run.name + " (" + run.actions.length() + " действий)");
                running = null;
                return;
            }
            final int stepNo = ++run.index;
            JSONObject action = run.actions.getJSONObject(stepNo - 1);
            String type = action.getString("type");
            LogBook.write(this, "STEP " + stepNo + "/" + run.actions.length() + " " + type + " — start");
            switch (type) {
                case "wait":
                    handler.postDelayed(() -> step(run), action.getInt("ms"));
                    break;
                case "log":
                    LogBook.write(this, "NOTE " + run.name + ": " + action.getString("message"));
                    step(run);
                    break;
                case "tap":
                    if(isSamsungRecordStep(run,stepNo,action))
                        smartSamsungRecordTap(run,action.getInt("x"),action.getInt("y"),stepNo);
                    else tapXY(run,action.getInt("x"),action.getInt("y"),stepNo);
                    break;
                case "tap_text":
                    clickText(run, action.getString("text"), stepNo);
                    break;
                case "wait_text":
                    pollText(run, action.getString("text"), System.currentTimeMillis() + action.optInt("timeoutMs", 8000), stepNo);
                    break;
                default:
                    stop("Неизвестная команда " + type);
            }
        } catch (Exception e) {
            LogBook.write(this, "ERROR " + run.name + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
            stop("Ошибка выполнения");
        }
    }

    private void pollText(ScriptRun run, String text, long until, int stepNo) {
        if (running != run) return;
        if (screenContains(text)) {
            LogBook.write(this, "STEP " + stepNo + " wait_text OK text='" + text + "'");
            step(run);
        } else if (System.currentTimeMillis() < until) {
            handler.postDelayed(() -> pollText(run, text, until, stepNo), 300);
        } else {
            LogBook.write(this, "STEP " + stepNo + " wait_text TIMEOUT text='" + text + "'");
            stop("Текст на экране не обнаружен");
        }
    }

    private boolean screenContains(String wanted) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        return nodeContains(root, wanted.toLowerCase(Locale.ROOT), 0);
    }
    private boolean nodeContains(AccessibilityNodeInfo node, String wanted, int depth) {
        if (node == null || depth > 35) return false;
        CharSequence txt = node.getText();
        CharSequence desc = node.getContentDescription();
        if (txt != null && txt.toString().toLowerCase(Locale.ROOT).contains(wanted)) return true;
        if (desc != null && desc.toString().toLowerCase(Locale.ROOT).contains(wanted)) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (nodeContains(node.getChild(i), wanted, depth + 1)) return true;
        }
        return false;
    }

    private boolean isSamsungRecordStep(ScriptRun run,int stepNo,JSONObject action) {
        return "call_active".equals(run.trigger)&&run.name.toLowerCase(Locale.ROOT).contains("samsung")&&
            stepNo==4&&action.optInt("x",-1)==500&&action.optInt("y",-1)==1520;
    }

    private boolean samsungStartLabel(String text) {
        if(text==null)return false;
        String s=text.trim().toLowerCase(Locale.ROOT);
        if(s.contains("stop")||s.contains("pause")||s.contains("останов")||
            s.contains("приостанов")||s.contains("beenden")||s.contains("stoppen"))return false;
        return s.equals("записать")||s.equals("запись")||s.equals("записать разговор")||
            s.equals("начать запись")||s.equals("запись вызова")||
            s.equals("record")||s.equals("record call")||s.equals("start recording")||
            s.equals("aufnehmen")||s.equals("anruf aufnehmen")||s.equals("aufzeichnen")||
            s.equals("anruf aufzeichnen")||s.equals("aufnahme starten")||s.equals("anrufaufnahme");
    }
    private AccessibilityNodeInfo findSamsungStartButton(AccessibilityNodeInfo n,int depth) {
        if(n==null||depth>35)return null;
        CharSequence a=n.getText(), b=n.getContentDescription();
        if((a!=null&&samsungStartLabel(a.toString()))||
           (b!=null&&samsungStartLabel(b.toString()))) {
            Rect r=new Rect();n.getBoundsInScreen(r);
            if(!r.isEmpty()&&n.isVisibleToUser())return n;
        }
        for(int i=0;i<n.getChildCount();i++) {
            AccessibilityNodeInfo child=findSamsungStartButton(n.getChild(i),depth+1);
            if(child!=null)return child;
        }
        return null;
    }
    private void smartSamsungRecordTap(ScriptRun run,int fx,int fy,int stepNo) {
        AccessibilityNodeInfo btn=findSamsungStartButton(getRootInActiveWindow(),0);
        if(btn!=null) {
            Rect r=new Rect();btn.getBoundsInScreen(r);
            AccessibilityNodeInfo cur=btn;
            for(int i=0;cur!=null&&i<5;i++,cur=cur.getParent()) {
                if(cur.isClickable()&&cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    LogBook.write(this,"PHONE RECORD BUTTON clicked by accessibility");
                    handler.postDelayed(this::checkSamsungRecording,3800);
                    step(run);
                    return;
                }
            }
            LogBook.write(this,"PHONE RECORD BUTTON found at "+r.centerX()+","+r.centerY()+"; tapping center");
            tapXY(run,r.centerX(),r.centerY(),stepNo);
            return;
        }
        LogBook.write(this,"PHONE RECORD BUTTON unavailable in accessibility; fallback tap "+fx+","+fy);
        tapXY(run,fx,fy,stepNo);
    }
    private boolean recordingIndicator(AccessibilityNodeInfo n,int depth) {
        if(n==null||depth>35)return false;
        CharSequence[] texts={n.getText(),n.getContentDescription()};
        for(CharSequence v:texts)if(v!=null) {
            String s=v.toString().trim().toLowerCase(Locale.ROOT);
            if(s.contains("stop recording")||s.contains("stop record")||
               s.contains("остановить запись")||s.contains("aufzeichnung stoppen")||
               s.contains("aufnahme stoppen")||s.contains("aufnahme beenden")||
               s.contains("recording in progress")||s.contains("запись идёт")||
               s.contains("запись идет"))return true;
        }
        for(int i=0;i<n.getChildCount();i++)if(recordingIndicator(n.getChild(i),depth+1))return true;
        return false;
    }
    private void checkSamsungRecording() {
        if(!TelephonyManager.EXTRA_STATE_OFFHOOK.equals(lastPhoneState)) {
            LogBook.write(this,"PHONE RECORD VERIFY: call ended, audio status not confirmed");
            return;
        }
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null||root.getPackageName()==null||
           !"com.samsung.android.incallui".contentEquals(root.getPackageName())) {
            LogBook.write(this,"PHONE RECORD VERIFY: in-call window unavailable");return;
        }
        if(recordingIndicator(root,0))
            LogBook.write(this,"PHONE RECORD VERIFY: recording indicator found");
        else
            LogBook.write(this,"PHONE RECORD VERIFY: UNKNOWN — gesture alone does not confirm saved audio");
    }

    private void clickText(ScriptRun run, String text, int stepNo) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            LogBook.write(this, "STEP " + stepNo + " tap_text FAILED: no accessibility root");
            stop("Не виден интерфейс"); return;
        }
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes == null || nodes.isEmpty()) {
            LogBook.write(this, "STEP " + stepNo + " tap_text FAILED: text not found: " + text);
            stop("Кнопка не найдена"); return;
        }
        AccessibilityNodeInfo found = nodes.get(0);
        AccessibilityNodeInfo cur = found;
        for (int i = 0; i < 8 && cur != null; i++) {
            if (cur.isClickable() && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                LogBook.write(this, "STEP " + stepNo + " tap_text SUCCESS — ACTION_CLICK dispatched");
                step(run); return;
            }
            cur = cur.getParent();
        }
        Rect bounds = new Rect();
        found.getBoundsInScreen(bounds);
        if (bounds.isEmpty()) { stop("Кнопка имеет пустые координаты"); return; }
        tapXY(run, bounds.centerX(), bounds.centerY(), stepNo);
    }

    private void tapXY(ScriptRun run, int x, int y, int stepNo) {
        if (running != run) return;
        if (isScreenLocked()) {
            LogBook.write(this, "STEP " + stepNo + " tap SKIPPED — locked");
            stop("Заблокированный экран"); return;
        }
        final Path path = new Path(); path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 100);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        final boolean[] handled = {false};
        boolean accepted;
        try {
            accepted = dispatchGesture(gesture, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription description) {
                    if (handled[0] || running != run) return;
                    handled[0] = true;
                    LogBook.write(AutomationService.this, "STEP " + stepNo + " tap COMPLETED x=" + x + " y=" + y);
                    if("call_active".equals(run.trigger) &&
                       run.name.toLowerCase(Locale.ROOT).contains("samsung")&&stepNo==4)
                        handler.postDelayed(AutomationService.this::checkSamsungRecording,3800);
                    step(run);
                }
                @Override public void onCancelled(GestureDescription description) {
                    if (handled[0] || running != run) return;
                    handled[0] = true;
                    LogBook.write(AutomationService.this, "STEP " + stepNo + " tap CANCELLED x=" + x + " y=" + y);
                    stop("Жест отменён Android");
                }
            }, handler);
        } catch (Exception e) {
            LogBook.write(this, "STEP " + stepNo + " tap ERROR: " + e);
            stop("Ошибка жеста"); return;
        }
        if (!accepted) {
            LogBook.write(this, "STEP " + stepNo + " tap REJECTED by AccessibilityService");
            stop("Android не принял жест"); return;
        }
        handler.postDelayed(() -> {
            if (!handled[0] && running == run) {
                handled[0] = true;
                LogBook.write(AutomationService.this, "STEP " + stepNo + " tap TIMEOUT");
                stop("Нет ответа об исполнении жеста");
            }
        }, 2500);
    }

    private static final class ScriptRun {
        final String name;
        final String trigger;
        final JSONArray actions;
        int index = 0;
        ScriptRun(JSONObject script, String trigger) throws Exception {
            this.name = script.getString("name");
            this.actions = script.getJSONArray("actions");
            this.trigger = trigger;
        }
    }
}
