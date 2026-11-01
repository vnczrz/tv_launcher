package tv.voxclean.spelunkr;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/** spelunkr.tv full screen: the remote's keys go to the page, Back walks the page history and then leaves. */
public class MainActivity extends Activity {
    private static final String TAG = "Spelunkr";
    private static final String HOME_URL = "https://spelunkr.tv/";
    // Android keeps Back from the page; offer it to the site as Escape first (it closes popups, clears the remote
    // highlight and leaves the watch page on Escape). Its popups close without preventDefault, so "taken" means the
    // page changed: address, open popups or focus. SEND_BACK records that state and sends Escape; BACK_TAKEN compares.
    private static final String PAGE_STATE = "(location.href+'|'+[...document.querySelectorAll('[role=dialog],"
            + "[class*=popover],[class*=signin],[class*=panel],[class*=popup]')].filter(function(e){"
            + "return e.getClientRects().length;}).length+'|'+(document.activeElement&&document.activeElement.className))";
    private static final String SEND_BACK = "(function(){window.__voxBack=" + PAGE_STATE + ";"
            + "(document.activeElement||document.body).dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',"
            + "code:'Escape',keyCode:27,bubbles:true,cancelable:true}));})()";
    private static final String BACK_TAKEN = "(" + PAGE_STATE + "!==window.__voxBack)";
    private static final long BACK_SETTLE_MS = 200;
    // The site's TV theme keeps a 1392 px column centred (--8s-gutter, max-width 1392px) and sizes its text in px
    // variables. Fill the screen and scale the text up by TEXT_SCALE; the guide and watch page still fit 1080 tall.
    // Runs once per document (the style tag is the guard, so the scale never compounds).
    private static final double TEXT_SCALE = 1.15;
    private static final String FIT_PAGE = "(function(){if(document.getElementById('vox-fit'))return;"
            + "var k=" + TEXT_SCALE + ",cs=getComputedStyle(document.documentElement),v='';"
            + "['text','small','title','big','label','nav','num','rownum','name'].forEach(function(n){"
            + "['s','lh'].forEach(function(p){var x=parseFloat(cs.getPropertyValue('--8s-'+n+'-'+p));"
            + "if(x)v+='--8s-'+n+'-'+p+':'+(x*k).toFixed(2)+'px;';});});"
            + "var s=document.createElement('style');s.id='vox-fit';"
            + "s.textContent='html.theme-8s{--8s-gutter:24px;font-size:'+(k*100)+'%;'+v+'}'"
            + "+'html.theme-8s .hp-grid-peek .tv-grid,html.theme-8s .directory-main{max-width:none}';"
            + "document.head.appendChild(s);})()";

    private FrameLayout root;
    private WebView web;
    private View fullscreenVideo;
    private WebChromeClient.CustomViewCallback fullscreenCallback;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Awake while a channel is on; the screensaver comes back once the app is left.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        // One CSS pixel per screen pixel: the site's guide and watch page are laid out to fit 1920x1080 exactly,
        // and at the TV's default 2x they run off the bottom.
        web.setInitialScale(100);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(FIT_PAGE, null);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    Log.w(TAG, "load failed: " + request.getUrl() + " (" + error.getErrorCode() + " "
                            + error.getDescription() + ")");
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                exitFullscreenVideo();
                fullscreenVideo = view;
                fullscreenCallback = callback;
                root.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.GONE);
            }

            @Override
            public void onHideCustomView() {
                exitFullscreenVideo();
            }
        });

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            web.loadUrl(HOME_URL);
        }
        web.requestFocus();
    }

    private void exitFullscreenVideo() {
        if (fullscreenVideo == null) return;
        root.removeView(fullscreenVideo);
        fullscreenVideo = null;
        web.setVisibility(View.VISIBLE);
        web.requestFocus();
        if (fullscreenCallback != null) fullscreenCallback.onCustomViewHidden();
        fullscreenCallback = null;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (fullscreenVideo != null) {
            exitFullscreenVideo();
            return;
        }
        web.evaluateJavascript(SEND_BACK, null);
        web.postDelayed(() -> web.evaluateJavascript(BACK_TAKEN, taken -> {
            if ("true".equals(taken)) return;
            if (web.canGoBack()) {
                web.goBack();
            } else {
                finish();
            }
        }), BACK_SETTLE_MS);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    // Leaving by Home (or anything else that covers the app) closes it: the site keeps playing while hidden, and the
    // tile should open a fresh page that tunes in live.
    @Override
    protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations()) finish();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
