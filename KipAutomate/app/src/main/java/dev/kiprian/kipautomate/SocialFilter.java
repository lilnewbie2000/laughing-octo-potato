package dev.kiprian.kipautomate;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class SocialFilter {
    private static final String TT="com.zhiliaoapp.musically", IG="com.instagram.android";
    private final AutomationService service;
    private final Handler handler;
    private final WindowManager wm;
    private JSONObject ttConfig,igConfig;
    private String active="",candidate="",lastDecision="";
    private long candidateAt,lastSwipe,lastBack,lastTick,lastDecisionAt;
    private boolean paused,scheduled,gestureBusy,overlayFailed;
    private TextView blocker;
    private final Rect blockerRect=new Rect();
    private int blockedCount;

    SocialFilter(AutomationService service,Handler handler) {
        this.service=service;this.handler=handler;
        wm=(WindowManager)service.getSystemService(AccessibilityService.WINDOW_SERVICE);
        reload();
    }
    void reload() {
        ttConfig=null;igConfig=null;
        try {
            JSONArray a=ScriptStore.validate(ScriptStore.read(service));
            for(int k=0;k<a.length();k++) {
                JSONObject o=a.getJSONObject(k);
                if(!o.optBoolean("enabled",false))continue;
                JSONObject t=o.optJSONObject("trigger");
                JSONArray steps=o.optJSONArray("actions");
                if(t==null||steps==null||steps.length()!=1||!"monitor".equals(t.optString("type")))continue;
                JSONObject cfg=steps.optJSONObject(0);
                if(cfg==null||!"social_filter".equals(cfg.optString("type")))continue;
                String p=t.optString("package");
                if(TT.equals(p))ttConfig=cfg;
                if(IG.equals(p))igConfig=cfg;
            }
            LogBook.write(service,"SOCIAL CONFIG TikTok="+(ttConfig!=null)+" Instagram="+(igConfig!=null));
        } catch(Exception e){LogBook.write(service,"SOCIAL CONFIG ERROR "+e.getMessage());}
        if(ttConfig==null&&igConfig==null) hideBlocker();
    }
    void togglePaused(){paused=!paused;if(paused)hideBlocker();else schedule();LogBook.write(service,paused?"SOCIAL PAUSED":"SOCIAL ENABLED");}
    boolean isPaused(){return paused;}
    void shutdown(){paused=true;active="";hideBlocker();}
    void onEvent(AccessibilityEvent event) {
        if(paused||event==null||event.getPackageName()==null)return;
        String pkg=event.getPackageName().toString();
        if(pkg.equals(service.getPackageName()))return;
        if(!TT.equals(pkg)&&!IG.equals(pkg)){
            if(event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                !pkg.contains("honeyboard")&&!pkg.contains("inputmethod")){active="";hideBlocker();candidate="";}
            return;
        }
        if((TT.equals(pkg)?ttConfig:igConfig)==null){hideBlocker();return;}
        if(!pkg.equals(active)){active=pkg;candidate="";hideBlocker();LogBook.write(service,"SOCIAL ENTER "+pkg);}
        schedule();
    }
    private void schedule() {
        if(scheduled||paused||active.isEmpty())return;
        scheduled=true;
        handler.postDelayed(()->{
            scheduled=false;
            if(!paused&&!active.isEmpty()){tick();schedule();}
        },220);
    }
    private static final class Item {
        String text,id;Rect r;boolean selected,edit;
        Item(String t,String id,Rect r,boolean selected,boolean edit){this.text=t;this.id=id;this.r=r;this.selected=selected;this.edit=edit;}
    }
    private static void collect(AccessibilityNodeInfo n,List<Item> a,int depth) {
        if(n==null||depth>32||a.size()>540)return;
        Rect r=new Rect();n.getBoundsInScreen(r);
        String t=n.getText()==null?"":n.getText().toString();
        if(n.getContentDescription()!=null)t+=" "+n.getContentDescription();
        if(n.getHintText()!=null)t+=" "+n.getHintText();
        String id=n.getViewIdResourceName()==null?"":n.getViewIdResourceName();
        String cls=n.getClassName()==null?"":n.getClassName().toString();
        if(!t.trim().isEmpty()||!id.isEmpty()||cls.contains("EditText"))a.add(new Item(t.trim().toLowerCase(Locale.ROOT),id.toLowerCase(Locale.ROOT),r,n.isSelected(),cls.contains("EditText")));
        for(int i=0;i<n.getChildCount()&&a.size()<540;i++)collect(n.getChild(i),a,depth+1);
    }
    private static boolean visible(Item i,Point s){
        return i.r.width()>0&&i.r.height()>0&&i.r.left>=0&&i.r.top>=0&&i.r.right<=s.x&&i.r.bottom<=s.y;
    }
    private static boolean eq(Item i,String...a){for(String v:a)if(i.text.equals(v))return true;return false;}
    private static boolean reels(List<Item> a,Point s){
        for(Item i:a)if(visible(i,s)){
            if(i.id.contains("clips_viewer")||i.id.contains("reels_viewer")||i.id.contains("clips_video")||i.id.contains("reels_video"))return true;
            if(i.selected&&eq(i,"reels","рилс","рилсы","clips"))return true;
        }
        return false;
    }
    private static Item commentButton(List<Item> a,Point s,String p) {
        Item found=null;
        for(Item i:a) {
            if(!visible(i,s)||i.r.centerX()<s.x*.63||i.r.centerY()<s.y*.2||i.r.centerY()>s.y*.94||
                i.r.width()>s.x*.38||i.r.height()>s.y*.18)continue;
            boolean match=TT.equals(p)?
                i.id.endsWith(":id/eqi")||i.text.startsWith("read or add comments")||i.text.startsWith("читать или добавить комментар"):
                i.id.contains("clips_comment")||i.id.contains("reels_comment")||i.id.contains("clips_ufi_comment")||
                eq(i,"comment","comments","комментарии","комментировать");
            if(match&&(found==null||i.r.width()*i.r.height()>found.r.width()*found.r.height()))found=i;
        }
        return found;
    }
    private static boolean commentPanel(List<Item> a,Point s){
        boolean input=false,title=false;
        for(Item i:a) {
            if(!visible(i,s))continue;
            if(i.edit&&(i.text.contains("comment")||i.text.contains("комментар")||i.text.contains("kommentar")))input=true;
            if(i.r.centerY()<s.y*.68 && (eq(i,"comments","комментарии","kommentare")||i.text.matches("^comments? [0-9]+.*")||i.text.matches("^[0-9]+ comments?$")))title=true;
        }
        return input&&title;
    }
    private static boolean isAd(List<Item> a,Point s,String p){
        for(Item i:a){
            if(!visible(i,s)||i.r.centerY()>s.y*.97)continue;
            if(TT.equals(p)){
                if(i.r.centerY()<s.y*.5||i.r.centerX()>s.x*.67)continue;
                if(i.id.endsWith(":id/iar")&&eq(i,"ad","advertisement","sponsored","реклама","anzeige"))return true;
                if(eq(i,"sponsored","реклама","gesponsert")&&i.r.width()<s.x*.45)return true;
            }else if(IG.equals(p)){
                if(eq(i,"sponsored","gesponsert","реклама","advertisement")&&i.r.width()<s.x*.65)return true;
            }
        }
        return false;
    }
    private static boolean isLive(List<Item> a,Point s){
        boolean l=false,g=false;
        for(Item i:a)if(visible(i,s)){
            if((eq(i,"live","прямой эфир","en direct")||i.id.contains("live_badge"))&&i.r.centerY()<s.y*.45)l=true;
            if(i.text.contains("send gift")||i.text.contains("подарок")||i.id.contains("live_gift"))g=true;
        }
        return l&&g;
    }
    private static boolean isSeries(List<Item> a,Point s){
        boolean l=false,p=false;
        for(Item i:a)if(visible(i,s)){
            if(eq(i,"series","сериал","сериалы","серия")||i.id.contains("series_badge")||i.id.contains("series_link"))l=true;
            if(i.text.contains("purchase series")||i.text.contains("unlock series")||i.text.contains("buy series")||
               i.text.contains("купить серию")||i.id.contains("series_purchase"))p=true;
        }
        return l&&p;
    }
    private void tick() {
        long now=SystemClock.uptimeMillis();
        if(now-lastTick<100)return;lastTick=now;
        AccessibilityNodeInfo root=service.getRootInActiveWindow();
        if(root==null||root.getPackageName()==null){hideBlocker();return;}
        String pkg=root.getPackageName().toString();
        if(!pkg.equals(active)){
            if(!pkg.contains("honeyboard")&&!pkg.contains("inputmethod")){active="";hideBlocker();}
            return;
        }
        JSONObject cfg=TT.equals(pkg)?ttConfig:igConfig;
        if(cfg==null){hideBlocker();return;}
        Point s=new Point();wm.getDefaultDisplay().getRealSize(s);
        List<Item> items=new ArrayList<>();collect(root,items,0);
        boolean video=TT.equals(pkg)||reels(items,s);
        if(cfg.optBoolean("blockComments",true)) {
            if(video&&commentPanel(items,s)){
                hideBlocker();
                if(now-lastBack>460&&!gestureBusy){lastBack=now;boolean b=service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                    LogBook.write(service,"SOCIAL COMMENT CLOSE "+pkg+" accepted="+b);}
                return;
            }
            Item button=video?commentButton(items,s,pkg):null;
            if(button!=null&&cfg.optBoolean("overlayComments",true))showBlocker(button.r,pkg);else hideBlocker();
        }else hideBlocker();
        if(gestureBusy||now-lastSwipe<1050||!video)return;
        String reason="";
        if(cfg.optBoolean("skipAds",true)&&isAd(items,s,pkg))reason="AD";
        else if(TT.equals(pkg)&&cfg.optBoolean("skipLive",false)&&isLive(items,s))reason="LIVE";
        else if(TT.equals(pkg)&&cfg.optBoolean("skipSeries",false)&&isSeries(items,s))reason="SERIES";
        if(reason.isEmpty()){candidate="";return;}
        if(!reason.equals(candidate)){
            candidate=reason;candidateAt=now;
            if(!reason.equals(lastDecision)||now-lastDecisionAt>5000){
                LogBook.write(service,"SOCIAL CANDIDATE "+pkg+" "+reason+" (verify visible + stable)");
                lastDecision=reason;lastDecisionAt=now;
            }
            return;
        }
        if(now-candidateAt<Math.max(150,cfg.optInt("stableMs",180)))return;
        candidate="";lastSwipe=now;hideBlocker();swipe(s,pkg,reason);
    }
    private void swipe(Point s,String pkg,String why) {
        if(gestureBusy)return;gestureBusy=true;
        Path path=new Path();path.moveTo(s.x*.5f,s.y*.8f);path.lineTo(s.x*.5f,s.y*.29f);
        GestureDescription g=new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path,0,160)).build();
        try {
            boolean accepted=service.dispatchGesture(g,new AccessibilityService.GestureResultCallback(){
                @Override public void onCompleted(GestureDescription d){gestureBusy=false;LogBook.write(service,"SOCIAL SWIPE COMPLETED "+pkg+" "+why);}
                @Override public void onCancelled(GestureDescription d){gestureBusy=false;LogBook.write(service,"SOCIAL SWIPE CANCELLED "+pkg+" "+why);}
            },handler);
            if(!accepted){gestureBusy=false;LogBook.write(service,"SOCIAL SWIPE REJECTED "+pkg+" "+why);}
        }catch(Exception e){gestureBusy=false;LogBook.write(service,"SOCIAL SWIPE ERROR "+e.getMessage());}
    }
    private void showBlocker(Rect r,String pkg) {
        if(overlayFailed||wm==null)return;
        if(blocker!=null&&blockerRect.equals(r))return;
        hideBlocker();
        try {
            TextView v=new TextView(service);
            v.setText("×");v.setTextSize(20);v.setTextColor(Color.WHITE);v.setGravity(Gravity.CENTER);
            GradientDrawable d=new GradientDrawable();d.setColor(0x77121d2e);d.setCornerRadius(18);d.setStroke(2,0xaaffffff);v.setBackground(d);
            v.setOnTouchListener((view,event)->{
                if(event.getAction()==MotionEvent.ACTION_DOWN){
                    blockedCount++;LogBook.write(service,"SOCIAL COMMENT BLOCKED "+pkg+" count="+blockedCount);
                }
                return true;
            });
            WindowManager.LayoutParams lp=new WindowManager.LayoutParams(r.width(),r.height(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,android.graphics.PixelFormat.TRANSLUCENT);
            lp.gravity=Gravity.TOP|Gravity.LEFT;lp.x=r.left;lp.y=r.top;
            wm.addView(v,lp);blocker=v;blockerRect.set(r);
            LogBook.write(service,"SOCIAL COMMENT BLOCKER ON "+pkg+" rect="+r.toShortString());
        }catch(Exception e){overlayFailed=true;blocker=null;blockerRect.setEmpty();LogBook.write(service,"SOCIAL BLOCKER ERROR "+e.getMessage());}
    }
    private void hideBlocker(){
        if(blocker!=null){try{wm.removeViewImmediate(blocker);}catch(Exception ignored){}blocker=null;blockerRect.setEmpty();}
    }
}
