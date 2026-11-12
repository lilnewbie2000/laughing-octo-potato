package dev.kiprian.kipautomate;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.View;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Conservative, script-controlled monitor for TikTok video feeds and Instagram Reels.
 * No screen capture or raw captions are persisted. All gestures are auditable.
 */
final class SocialFilter {
    private static final String TT = "com.zhiliaoapp.musically";
    private static final String IG = "com.instagram.android";
    private final AutomationService service;
    private final Handler handler;
    private final WindowManager wm;
    private JSONObject ttConfig, igConfig;
    private String activePackage="";
    private boolean paused=false, ticking=false, gestureBusy=false, overlayFailed=false;
    private View blocker;
    private WindowManager.LayoutParams blockerParams;
    private final Rect blockerBounds = new Rect();
    private long lastTick=0, lastSwipe=0, lastBack=0, lastReelSeen=0, lastDiagnostic=0;
    private String lastCandidate="", lastDecision="";
    private long candidateSince=0, lastDecisionTime=0;
    private int blockedTaps=0;

    SocialFilter(AutomationService s, Handler h) {
        service=s; handler=h; wm=(WindowManager)s.getSystemService(AccessibilityService.WINDOW_SERVICE);
        reload();
    }

    void reload() {
        ttConfig=null; igConfig=null;
        try {
            JSONArray scripts=ScriptStore.validate(ScriptStore.read(service));
            for(int i=0;i<scripts.length();i++) {
                JSONObject script=scripts.getJSONObject(i);
                if(!script.optBoolean("enabled",false)) continue;
                JSONObject trigger=script.optJSONObject("trigger");
                if(trigger==null || !"monitor".equals(trigger.optString("type"))) continue;
                JSONArray actions=script.optJSONArray("actions");
                if(actions==null || actions.length()!=1) continue;
                JSONObject cfg=actions.optJSONObject(0);
                if(cfg==null || !"social_filter".equals(cfg.optString("type"))) continue;
                String pkg=trigger.optString("package");
                if(TT.equals(pkg)) ttConfig=cfg;
                if(IG.equals(pkg)) igConfig=cfg;
            }
            LogBook.write(service,"SOCIAL CONFIG: TikTok="+(ttConfig!=null)+", Instagram="+(igConfig!=null)+", paused="+paused);
        } catch(Exception e) { LogBook.write(service,"SOCIAL CONFIG ERROR: "+e.getMessage()); }
        if(ttConfig==null&&igConfig==null) { hideBlocker(); ticking=false; }
    }

    boolean isPaused() {return paused;}
    void togglePaused() {
        paused=!paused;
        if(paused) {hideBlocker(); lastCandidate="";}
        LogBook.write(service,"SOCIAL "+(paused?"PAUSED":"RESUMED"));
        if(!paused) schedule(0);
    }

    void onEvent(AccessibilityEvent e) {
        if(e==null || paused) return;
        String pkg=e.getPackageName()==null?"":e.getPackageName().toString();
        if(pkg.equals(service.getPackageName())) return;
        if(!TT.equals(pkg)&&!IG.equals(pkg)) {
            // Events from the keyboard are not necessarily changes to the foreground app.
            if(e.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                    !pkg.startsWith("com.samsung.android.honeyboard") &&
                    !pkg.startsWith("com.google.android.inputmethod")) {
                if(!activePackage.isEmpty()) {
                    activePackage=""; hideBlocker(); resetCandidate();
                }
            }
            return;
        }
        if((TT.equals(pkg)?ttConfig:igConfig)==null) {hideBlocker();return;}
        if(!pkg.equals(activePackage)) {
            activePackage=pkg;
            hideBlocker();resetCandidate();
            LogBook.write(service,"SOCIAL ENTER: "+(TT.equals(pkg)?"TikTok":"Instagram"));
        }
        // Use window-event-driven scans plus a bounded fast poll while a supported app is foreground.
        schedule(0);
    }

    void shutdown() {paused=true;hideBlocker();ticking=false;}

    private void schedule(long delay) {
        if(ticking || paused || activePackage.isEmpty()) return;
        ticking=true;
        handler.postDelayed(() -> {
            ticking=false;
            if(paused || activePackage.isEmpty()) return;
            tick();
            if(!paused && !activePackage.isEmpty()) schedule(230);
        }, delay);
    }

    private void tick() {
        long now=SystemClock.uptimeMillis();
        if(now-lastTick<100) return;
        lastTick=now;
        AccessibilityNodeInfo root=service.getRootInActiveWindow();
        if(root==null || root.getPackageName()==null) {hideBlocker();return;}
        String pkg=root.getPackageName().toString();
        if(!pkg.equals(activePackage)) {
            // No accidental gestures against a different app or keyboard.
            if(!pkg.startsWith("com.samsung.android.honeyboard") &&
                    !pkg.startsWith("com.google.android.inputmethod")) {
                activePackage="";hideBlocker();resetCandidate();
            }
            return;
        }
        JSONObject cfg=TT.equals(pkg)?ttConfig:igConfig;
        if(cfg==null) {hideBlocker();return;}
        Point size=new Point();
        try {wm.getDefaultDisplay().getRealSize(size);}catch(Exception e){size.set(service.getResources().getDisplayMetrics().widthPixels,service.getResources().getDisplayMetrics().heightPixels);}
        if(size.x<=0||size.y<=0) return;
        List<Item> items=new ArrayList<>();
        collect(root,items,0,550);
        boolean reels=TT.equals(pkg) || isReels(items,size);
        if(IG.equals(pkg) && reels) lastReelSeen=now;

        if(cfg.optBoolean("blockComments",true)) {
            if((TT.equals(pkg)||reels) && hasCommentPanel(items,size)) {
                hideBlocker();
                if(now-lastBack>400 && !gestureBusy) {
                    lastBack=now;
                    boolean back=service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                    LogBook.write(service,"SOCIAL COMMENT CLOSE "+pkg+" back="+back);
                }
                return;
            }
            Item button=(TT.equals(pkg)||reels)?findCommentButton(items,pkg,size):null;
            if(cfg.optBoolean("overlayComments",true) && button!=null) showBlocker(button.rect,pkg);
            else hideBlocker();
        } else hideBlocker();

        // Record a brief, privacy-conscious diagnosis even if we fail to recognize a video.
        // Avoid logging captions, account names or any other user-created text.
        boolean ad=isAd(items,pkg,size);
        boolean live=TT.equals(pkg) && isLive(items,size);
        boolean series=TT.equals(pkg) && isSeries(items,size);
        if(cfg.optBoolean("debug",false) && now-lastDiagnostic>=3300) {
            lastDiagnostic=now;
            LogBook.write(service,"SOCIAL SCAN "+pkg+" nodes="+items.size()+
                " reels="+reels+" comment="+(findCommentButton(items,pkg,size)!=null)+" ad="+ad+" live="+live+" series="+series+
                " markers="+markerDiagnostic(items,size));
        }
        if(gestureBusy || now-lastSwipe<750) return;
        if(IG.equals(pkg) && !reels) {resetCandidate();return;} // no Instagram feed ad skips
        String reason="";
        if(cfg.optBoolean("skipAds",true) && ad) reason="AD";
        else if(TT.equals(pkg) && cfg.optBoolean("skipLive",false) && live) reason="LIVE";
        else if(TT.equals(pkg) && cfg.optBoolean("skipSeries",false) && series) reason="SERIES";
        if(reason.isEmpty()) {resetCandidate();return;}
        // Two consecutive visible snapshots prevent stale/offscreen label false positives.
        if(!reason.equals(lastCandidate)) {
            lastCandidate=reason;candidateSince=now;
            if(!reason.equals(lastDecision)||now-lastDecisionTime>5000) {
                LogBook.write(service,"SOCIAL CANDIDATE: "+pkg+" "+reason+" (checking stability)");
                lastDecision=reason;lastDecisionTime=now;
            }
            return;
        }
        if(now-candidateSince<Math.max(150,cfg.optInt("stableMs",180))) return;
        lastSwipe=now;resetCandidate();hideBlocker();swipeUp(pkg,reason,size);
    }

    private static final class Item {
        String text,id,cls;
        Rect rect;
        boolean selected,clickable,editable;
        Item(String s,String i,String c,Rect r,boolean sel,boolean click,boolean edit) {
            text=s;id=i;cls=c;rect=r;selected=sel;clickable=click;editable=edit;
        }
    }
    private static void collect(AccessibilityNodeInfo n,List<Item> out,int depth,int max) {
        if(n==null||depth>30||out.size()>=max) return;
        Rect r=new Rect();n.getBoundsInScreen(r);
        String t="";
        if(n.getText()!=null) t=n.getText().toString();
        if(n.getContentDescription()!=null) t+=(t.isEmpty()?"":" ")+n.getContentDescription().toString();
        if(n.getHintText()!=null) t+=(t.isEmpty()?"":" ")+n.getHintText().toString();
        String id=n.getViewIdResourceName()==null?"":n.getViewIdResourceName();
        String cls=n.getClassName()==null?"":n.getClassName().toString();
        if(!t.isEmpty()||!id.isEmpty()||cls.contains("EditText")) {
            out.add(new Item(t.toLowerCase(Locale.ROOT),id.toLowerCase(Locale.ROOT),cls,r,n.isSelected(),n.isClickable(),cls.contains("EditText")));
        }
        for(int i=0;i<n.getChildCount()&&out.size()<max;i++) collect(n.getChild(i),out,depth+1,max);
    }
    private static boolean visible(Item i,Point s) {
        Rect r=i.rect;
        if(r==null || r.width()<=0 || r.height()<=0) return false;
        int overlapX=Math.max(0,Math.min(r.right,s.x)-Math.max(0,r.left));
        int overlapY=Math.max(0,Math.min(r.bottom,s.y)-Math.max(0,r.top));
        // Reject stale and offscreen accessibility nodes, but tolerate slightly clipped labels.
        return overlapX>=r.width()*.65f && overlapY>=r.height()*.65f;
    }
    private static boolean exact(Item i,String...values) {
        for(String v:values) if(i.text.equals(v))return true;
        return false;
    }
    private static boolean isReels(List<Item> a,Point size) {
        boolean strong=false,topTitle=false;
        int rightSideControls=0;
        boolean rightComment=false,rightShare=false,rightLike=false;
        for(Item i:a) {
            if(!visible(i,size)) continue;
            String id=i.id;
            String t=i.text;
            int x=i.rect.centerX(),y=i.rect.centerY();
            // Avoid counting the ever-present bottom Reels navigation tab.
            if(id.contains("clips_viewer")||id.contains("reels_viewer")||
               id.contains("clips_video")||id.contains("reels_video")||
               id.contains("clips_ufi")||id.contains("reels_ufi")||
               id.contains("reel_player")||id.contains("clips_player")||
               id.contains("clips_comment")||id.contains("reels_comment")) strong=true;
            if(y<size.y*.20f && (exact(i,"reels","рилс","рилсы","clips") ||
                t.contains("reels tab selected"))) topTitle=true;
            if(i.selected && exact(i,"reels","рилс","рилсы","clips")) topTitle=true;
            if(x>size.x*.72f && y>size.y*.22f && y<size.y*.90f &&
                i.rect.width()<size.x*.32f && i.rect.height()<size.y*.13f) {
                if(isCommentLabel(i)) rightComment=true;
                if(t.startsWith("share") || t.startsWith("send to") || t.startsWith("поделиться") ||
                   t.startsWith("отправить") || id.contains("share_button"))rightShare=true;
                if(t.startsWith("like") || t.startsWith("unlike") || t.startsWith("нравится") ||
                   id.contains("like_button"))rightLike=true;
                if((isCommentLabel(i)||rightShare||rightLike)&&i.clickable)rightSideControls++;
            }
        }
        // For videos opened from Instagram feed, the vertical right-side action rail is
        // evidence of a full-screen Reel even without an explicit Reels accessibility ID.
        return strong || (topTitle && (rightComment || rightShare || rightLike)) ||
            (rightComment && (rightShare || rightLike)) || rightSideControls>=3;
    }
    private static boolean isCommentLabel(Item i) {
        String t=i.text;
        return i.id.contains("comment") || t.startsWith("comment") ||
            t.startsWith("view comments") || t.startsWith("view all comments") ||
            t.startsWith("add a comment") || t.startsWith("коммент") ||
            t.startsWith("посмотреть комментар") || t.startsWith("добавить комментар") ||
            t.startsWith("kommentier") || t.startsWith("kommentar") ||
            t.startsWith("kommentare");
    }
    private static Item findCommentButton(List<Item> a,String pkg,Point s) {
        Item best=null;
        for(Item i:a) {
            if(!visible(i,s)||i.rect.centerX()<s.x*0.64||i.rect.centerY()<s.y*0.20||i.rect.centerY()>s.y*0.93)continue;
            if(i.rect.width()>s.x*.36||i.rect.height()>s.y*.18)continue;
            boolean is=TT.equals(pkg) ?
                i.id.endsWith(":id/eqi") || i.text.startsWith("read or add comments") || i.text.startsWith("читать или добавить комментар") || i.text.matches("(?s)^\\d+ comments?$")
                : isCommentLabel(i);
            if(is && (best==null||i.rect.width()*i.rect.height()>best.rect.width()*best.rect.height()))best=i;
        }
        return best;
    }
    private static boolean hasCommentPanel(List<Item> a,Point s) {
        boolean input=false,head=false;
        for(Item i:a) {
            if(!visible(i,s)) continue;
            if(i.editable && (i.text.contains("comment")||i.text.contains("комментар")||i.text.contains("kommentar")||i.text.contains("ответить"))) input=true;
            if(i.rect.centerY()<s.y*.65 && (exact(i,"comments","комментарии","kommentare") || i.text.matches("^comments? \\d+.*") || i.text.matches("^\\d+ comments?$"))) head=true;
        }
        return input && head;
    }
    private static boolean shortLabel(Item i,Point s) {
        return !i.editable && i.rect.width()<s.x*.64f &&
            i.rect.height()<s.y*.12f && i.text.length()<90;
    }
    private static boolean adWords(String t) {
        if(t==null) return false;
        t=t.trim();
        // Whole leading word only: "adventure" and "add comments" must not match "ad".
        return t.matches("(?s)^(?:ad|advertisement|sponsored|promoted|gesponsert|anzeige|реклама|рекламный контент|paid partnership)(?:$|[\\s·•:—-].*)");
    }
    private static boolean isAd(List<Item> a,String pkg,Point s) {
        for(Item i:a) {
            if(!visible(i,s)||!shortLabel(i,s))continue;
            if(i.rect.centerX()>s.x*.78f || i.rect.centerY()<s.y*.11f || i.rect.centerY()>s.y*.97f)continue;
            if(!adWords(i.text))continue;
            if(TT.equals(pkg)) {
                if(i.id.endsWith(":id/iar")||i.id.contains("ad_badge")||i.id.contains("sponsored")||
                   i.id.contains("ad_label")||i.id.contains("ad_tag"))return true;
                // Fallback for TikTok UI variations where the exact ID changes.
                if(i.text.length()<=45 && i.rect.centerY()>s.y*.22f)return true;
            } else if(IG.equals(pkg)) {
                // Called only after a strong Instagram Reels identification.
                if(i.text.length()<=55)return true;
            }
        }
        return false;
    }
    private static boolean isLive(List<Item> a,Point s) {
        boolean badge=false, associatedUI=false;
        for(Item i:a) {
            if(!visible(i,s))continue;
            String t=i.text;
            boolean strongId=i.id.contains("live_badge")||i.id.contains("live_room")||i.id.contains("live_viewer");
            if(strongId && i.rect.centerY()<s.y*.65f)badge=true;
            // "LIVE" in the top navigation is always visible, even on ordinary videos.
            if(shortLabel(i,s) && i.rect.centerY()>s.y*.12f && i.rect.centerY()<s.y*.65f &&
               (exact(i,"live","прямой эфир","эфир","livestream")||t.startsWith("live now")))badge=true;
            if(t.contains("watch live")||t.contains("join live")||t.contains("смотреть эфир")||
               t.contains("присоединиться к эфиру")||t.contains("live viewers")||
               t.contains("viewers watching")||t.contains("send gift")||
               t.contains("отправить подарок")||i.id.contains("live_gift")||
               i.id.contains("live_viewer"))associatedUI=true;
        }
        // A clear "watch live" CTA is itself unambiguous, but an isolated LIVE tab is not.
        return (badge&&associatedUI)||hasLiveCta(a,s);
    }
    private static boolean hasLiveCta(List<Item>a,Point s) {
        for(Item i:a) if(visible(i,s)&&shortLabel(i,s)&&
            (i.text.startsWith("watch live")||i.text.startsWith("join live")||
             i.text.startsWith("смотреть эфир")||i.text.startsWith("присоединиться к эфиру")))return true;
        return false;
    }
    private static boolean isSeries(List<Item> a,Point s) {
        boolean badge=false,payment=false;
        for(Item i:a) {
            if(!visible(i,s))continue;
            String t=i.text;
            if(i.id.contains("series_badge")||i.id.contains("series_link")||
               i.id.contains("paid_series"))badge=true;
            if(t.contains("purchase series")||t.contains("buy series")||t.contains("unlock series")||
               t.contains("watch full series")||t.contains("buy episodes")||
               t.contains("купить серию")||t.contains("открыть серии")||
               t.contains("платный сериал")||t.contains("покупка сериала")||
               i.id.contains("series_purchase"))payment=true;
        }
        return payment || (badge && hasSeriesLink(a,s));
    }
    private static boolean hasSeriesLink(List<Item> a,Point s) {
        for(Item i:a)if(visible(i,s)&&shortLabel(i,s)&&
            (i.text.startsWith("series")||i.text.startsWith("сериалы")||
             i.text.startsWith("watch series")||i.text.startsWith("смотреть сериал")))return true;
        return false;
    }
    private static String markerDiagnostic(List<Item> a,Point s) {
        StringBuilder b=new StringBuilder();int count=0;
        for(Item i:a) {
            if(!visible(i,s))continue;
            String marker="";
            if(adWords(i.text))marker="AD_LABEL";
            else if(i.id.endsWith(":id/iar"))marker="TT_AD_ID";
            else if(i.id.contains("live_badge")||i.id.contains("live_room"))marker="LIVE_ID";
            else if(i.id.contains("series_badge")||i.id.contains("series_link"))marker="SERIES_ID";
            else if(i.id.contains("clips_viewer")||i.id.contains("reels_viewer")||
                    i.id.contains("clips_ufi")||i.id.contains("reels_ufi"))marker="REELS_ID";
            else if(exact(i,"live","series","reels","sponsored","реклама","прямой эфир"))marker="LABEL";
            if(marker.isEmpty())continue;
            if(count++>=7){b.append(" …");break;}
            if(b.length()>0)b.append(";");
            b.append(marker).append("@").append(100*i.rect.centerX()/Math.max(1,s.x)).append("%,")
                .append(100*i.rect.centerY()/Math.max(1,s.y)).append("%")
                .append("[").append(i.rect.width()).append("x").append(i.rect.height()).append("]");
        }
        return b.length()==0?"none":b.toString();
    }
    private void resetCandidate(){lastCandidate="";candidateSince=0;}
    private void swipeUp(String pkg,String reason,Point s) {
        if(gestureBusy)return;
        gestureBusy=true;
        Path p=new Path();p.moveTo(s.x*.50f,s.y*.79f);p.lineTo(s.x*.50f,s.y*.29f);
        GestureDescription g=new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p,0,165)).build();
        try {
            boolean ok=service.dispatchGesture(g,new AccessibilityService.GestureResultCallback(){
                @Override public void onCompleted(GestureDescription d){gestureBusy=false;LogBook.write(service,"SOCIAL SWIPE COMPLETED "+pkg+" reason="+reason);}
                @Override public void onCancelled(GestureDescription d){gestureBusy=false;LogBook.write(service,"SOCIAL SWIPE CANCELLED "+pkg+" reason="+reason);}
            },handler);
            if(!ok) {gestureBusy=false;LogBook.write(service,"SOCIAL SWIPE REJECTED "+pkg+" reason="+reason);}
        }catch(Exception e){gestureBusy=false;LogBook.write(service,"SOCIAL SWIPE ERROR: "+e.getMessage());}
    }
    private void showBlocker(Rect r,String pkg) {
        if(overlayFailed || wm==null || r==null || r.isEmpty()) return;
        // Accessibility sometimes reports only the glyph, not its whole tap area.
        Rect safe=new Rect(r);
        int minPx=(int)(48*service.getResources().getDisplayMetrics().density);
        if(safe.width()<minPx) safe.inset(-(minPx-safe.width())/2,0);
        if(safe.height()<minPx) safe.inset(0,-(minPx-safe.height())/2);
        if(blocker!=null && blockerBounds.equals(safe))return;
        hideBlocker();
        try {
            // 100% visually transparent touch shield: no cross, box, tint, or border.
            View v=new View(service);
            v.setBackgroundColor(Color.TRANSPARENT);
            v.setOnTouchListener((view,event)->{
                if(event.getAction()==MotionEvent.ACTION_DOWN){
                    blockedTaps++;
                    LogBook.write(service,"SOCIAL COMMENT TAP BLOCKED "+pkg+" count="+blockedTaps);
                }
                return true;
            });
            WindowManager.LayoutParams lp=new WindowManager.LayoutParams(safe.width(),safe.height(),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    android.graphics.PixelFormat.TRANSLUCENT);
            lp.gravity=Gravity.TOP|Gravity.LEFT;lp.x=safe.left;lp.y=safe.top;
            wm.addView(v,lp);blocker=v;blockerParams=lp;blockerBounds.set(safe);
            LogBook.write(service,"SOCIAL BLOCKER ON "+pkg+" invisible=true bounds="+safe.toShortString());
        }catch(Exception e){
            overlayFailed=true;blocker=null;blockerBounds.setEmpty();
            LogBook.write(service,"SOCIAL BLOCKER ERROR: "+e.getMessage());
        }
    }
    private void hideBlocker() {
        if(blocker!=null){try{wm.removeViewImmediate(blocker);}catch(Exception ignored){}blocker=null;blockerBounds.setEmpty();}
    }
}
