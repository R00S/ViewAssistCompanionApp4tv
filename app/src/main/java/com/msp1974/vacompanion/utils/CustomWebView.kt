package com.msp1974.vacompanion.utils

import android.annotation.SuppressLint
import kotlin.jvm.JvmOverloads
import android.content.Context
import android.content.res.Configuration
import android.view.MotionEvent
import android.content.res.Resources.NotFoundException
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.webkit.*
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING
import androidx.webkit.WebViewFeature
import com.msp1974.vacompanion.jsinterface.ViewAssistCallback
import com.msp1974.vacompanion.jsinterface.WebAppInterface
import com.msp1974.vacompanion.jsinterface.WebViewJavascriptInterface
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.settings.PageLoadingStage
import timber.log.Timber

@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class CustomWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : WebView(context, attrs, defStyleAttr) {

    lateinit private var customWebviewClient: CustomWebViewClient
    lateinit private var config: APPConfig


    private val log = Logger()
    private var requestDisallow = false
    private val androidInterface: Any = object : Any() {
        @JavascriptInterface
        fun requestScrollEvents() {
            requestDisallow = true
        }
    }

    fun initialise(config: APPConfig, customWebViewClient: CustomWebViewClient) {
        log.d("Initialising WebView")

        this.config = config
        this.customWebviewClient = customWebViewClient

        webViewClient = customWebViewClient
        setFocusable(true)
        setFocusableInTouchMode(true)

        setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT, false)
        setLayerType(LAYER_TYPE_HARDWARE, null)

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            safeBrowsingEnabled = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            textZoom = 100
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT

            webChromeClient = CustomWebChromeClient(context)
        }

        // Add JS interfaces
        removeJavascriptInterface("Android")
        addJavascriptInterface(androidInterface, "Android")

        if (webViewClient::class == CustomWebViewClient::class) {
            val webViewClientA = webViewClient as CustomWebViewClient
            removeJavascriptInterface("ViewAssistApp")
            addJavascriptInterface(WebAppInterface(webViewClientA.config, ViewAssistEventHandler), "ViewAssistApp")

            removeJavascriptInterface("externalApp")
            addJavascriptInterface(WebViewJavascriptInterface(this, AuthUtils(config).externalAuthCallback), "externalApp")
        }
    }

    val ViewAssistEventHandler = object : ViewAssistCallback {
        override fun onEvent(event: String, data: String) {
            //if (event == "location-changed") {
            //    Handler(Looper.getMainLooper()).post({
            //        setPageLoadingState(PageLoadingStage.LOADED)
            //    })
            //}
            Timber.d("Event received: $event, $data")
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (requestDisallow) {
            requestDisallowInterceptTouchEvent(true)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> requestDisallow = false
        }
        return super.onTouchEvent(event)
    }

    fun refreshDarkMode() {
        val nightModeFlag = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (nightModeFlag == Configuration.UI_MODE_NIGHT_YES) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(
                    settings,
                    WebSettingsCompat.FORCE_DARK_ON
                )
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
                WebSettingsCompat.setForceDarkStrategy(
                    settings,
                    DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING
                )
            }
        }
    }

    fun setZoomLevel(level: Int) {
        if (level == 0) {
            settings.useWideViewPort = true
        } else {
            settings.useWideViewPort = false
            setInitialScale(level)
        }

    }

    fun setTextSize(level: Int) {
        if (level == 0) {
            settings.textZoom = 100
        } else {
            settings.textZoom = level
        }

    }

    fun setPageLoadingState(stage: PageLoadingStage) {
        val w = webViewClient as CustomWebViewClient
        w.setPageLoadingState(stage)
    }

    fun refresh() {
        val url = AuthUtils.getURL(AuthUtils.getHAUrl(config))
        log.d("Loading URL: $url")
        loadUrl(url)
    }

    fun enableDPadNavigationAssist() {
        val script = """
            (function () {
                if (window.__vaDpadNavigationInstalled) return;
                window.__vaDpadNavigationInstalled = true;

                var FOCUSABLE_SELECTOR = [
                    'a[href]', 'button', 'input', 'select', 'textarea',
                    '[tabindex]:not([tabindex="-1"])',
                    '[role="button"]', '[role="link"]', '[role="checkbox"]',
                    '[role="tab"]', '[role="menuitem"]', '[role="switch"]',
                    '[contenteditable="true"]'
                ].join(',');

                // Vertical band height (px) used when sorting items within a group into
                // reading order.  Items whose cy values fall in the same band are treated
                // as the same row and sorted left-to-right; items in different bands are
                // sorted top-to-bottom.
                var ROW_BAND_PX = 30;

                // Milliseconds to wait after a SPA navigation before re-focusing, giving
                // Home Assistant time to render the new page content.
                var SPA_RENDER_DELAY_MS = 1000;

                // ── DOM helpers ──────────────────────────────────────────────────────

                // Walk up through shadow boundaries: use .host when .parentNode is null
                // (ShadowRoot.parentNode is null; ShadowRoot.host is the shadow-host element).
                function composedParent(n) { return n.parentNode || n.host || null; }

                function deepActive() {
                    var el = document.activeElement;
                    while (el && el.shadowRoot && el.shadowRoot.activeElement) {
                        el = el.shadowRoot.activeElement;
                    }
                    return el;
                }

                function isVisible(el) {
                    if (!el || el.disabled) return false;
                    if ((el.getAttribute && el.getAttribute('aria-hidden')) === 'true') return false;
                    var s = window.getComputedStyle(el);
                    return s.display !== 'none' && s.visibility !== 'hidden';
                }

                // ── Item collection ──────────────────────────────────────────────────

                // Returns [{el, rect, cx, cy}] for all visible focusable elements,
                // piercing every shadow root in the document tree.
                function collectItems() {
                    var raw = [], seen = [];
                    function collect(root) {
                        var m = root.querySelectorAll(FOCUSABLE_SELECTOR);
                        for (var i = 0; i < m.length; i++) {
                            if (isVisible(m[i]) && seen.indexOf(m[i]) === -1) {
                                seen.push(m[i]); raw.push(m[i]);
                            }
                        }
                        var all = root.querySelectorAll('*');
                        for (var j = 0; j < all.length; j++) {
                            if (all[j].shadowRoot) collect(all[j].shadowRoot);
                        }
                    }
                    collect(document);
                    return raw.map(function (el) {
                        var r = el.getBoundingClientRect();
                        return { el: el, rect: r, cx: r.left + r.width / 2, cy: r.top + r.height / 2 };
                    }).filter(function (it) { return it.rect.width > 0 && it.rect.height > 0; });
                }

                // ── Group detection ──────────────────────────────────────────────────

                // Walk up the composed DOM from el and return the first ancestor that:
                //   (a) has a non-zero bounding rect,
                //   (b) is "card-sized" – width ≤ 60 % viewport width OR height ≤ 60 % viewport height
                //       (catches narrow sidebars as well as short cards),
                //   (c) strictly encloses at least 2 of the collected items (1 px tolerance).
                // Falls back to el itself so every item always belongs to exactly one group.
                function groupAnchor(el, items) {
                    var mw = window.innerWidth  * 0.6;
                    var mh = window.innerHeight * 0.6;
                    var cur = composedParent(el);
                    while (cur && cur !== document && cur !== document.documentElement) {
                        if (typeof cur.getBoundingClientRect === 'function') {
                            var r = cur.getBoundingClientRect();
                            if (r.width > 0 && r.height > 0 && (r.width <= mw || r.height <= mh)) {
                                var n = 0;
                                for (var k = 0; k < items.length; k++) {
                                    var ir = items[k].rect;
                                    if (ir.left + 1 >= r.left && ir.right  - 1 <= r.right &&
                                            ir.top  + 1 >= r.top  && ir.bottom - 1 <= r.bottom) {
                                        n++;
                                        if (n >= 2) return cur;
                                    }
                                }
                            }
                        }
                        cur = composedParent(cur);
                    }
                    return el; // leaf: element is its own single-item group
                }

                // Build an ordered list of groups from a flat items array.
                // Each group: { items: [...sorted by reading order], cx, cy }
                // Groups themselves are sorted in reading order (top row first, left-to-right within a row).
                function buildGroups(items) {
                    if (!items.length) return [];
                    var anchors = [], gItems = [];
                    for (var i = 0; i < items.length; i++) {
                        var a = groupAnchor(items[i].el, items);
                        var idx = anchors.indexOf(a);
                        if (idx === -1) { anchors.push(a); gItems.push([items[i]]); }
                        else gItems[idx].push(items[i]);
                    }
                    var rowH = window.innerHeight * 0.3;
                    return anchors.map(function (a, i) {
                        // Sort items within a group by reading order (row by ROW_BAND_PX bands, then cx).
                        var gi = gItems[i].sort(function (x, y) {
                            var d = Math.floor(x.cy / ROW_BAND_PX) - Math.floor(y.cy / ROW_BAND_PX);
                            return d !== 0 ? d : x.cx - y.cx;
                        });
                        var ar = (typeof a.getBoundingClientRect === 'function')
                            ? a.getBoundingClientRect() : gi[0].rect;
                        return { items: gi, cx: ar.left + ar.width / 2, cy: ar.top + ar.height / 2 };
                    }).sort(function (a, b) {
                        // Sort groups in reading order: top row first (bands of 30 % of viewport
                        // height), then left-to-right within a row.
                        var ra = Math.floor(a.cy / rowH), rb = Math.floor(b.cy / rowH);
                        return ra !== rb ? ra - rb : a.cx - b.cx;
                    });
                }

                // ── Focus helpers ────────────────────────────────────────────────────

                function groupOf(groups) {
                    var active = deepActive();
                    if (!active) return -1;
                    for (var g = 0; g < groups.length; g++)
                        for (var i = 0; i < groups[g].items.length; i++)
                            if (groups[g].items[i].el === active) return g;
                    return -1;
                }

                function itemOf(g) {
                    var active = deepActive();
                    for (var i = 0; i < g.items.length; i++)
                        if (g.items[i].el === active) return i;
                    return -1;
                }

                function go(el) {
                    if (!el) return false;
                    try {
                        el.focus();
                        if (el.scrollIntoView) el.scrollIntoView({ block: 'nearest', inline: 'nearest' });
                        return true;
                    } catch (ignored) { return false; }
                }

                // ── D-pad key handler ────────────────────────────────────────────────

                // Capture phase so our handler runs before shadow-DOM component handlers.
                // We call stopPropagation whenever we move focus so that HA components cannot
                // steal it back.  At Up/Down group boundaries we deliberately do NOT consume
                // the event so the browser can scroll the page naturally.
                document.addEventListener('keydown', function (e) {
                    if (e.defaultPrevented) return;
                    var key = e.key;
                    if (key !== 'ArrowUp' && key !== 'ArrowDown' &&
                            key !== 'ArrowLeft' && key !== 'ArrowRight') return;

                    var items = collectItems();
                    if (!items.length) return;
                    var groups = buildGroups(items);
                    if (!groups.length) return;

                    var gIdx = groupOf(groups);
                    if (gIdx === -1) {
                        // No known focus: set initial focus to first item of first group.
                        go(groups[0].items[0].el);
                        e.stopPropagation(); e.preventDefault();
                        return;
                    }

                    var g = groups[gIdx];

                    if (key === 'ArrowRight') {
                        // Jump to first item of next group (wraps around).
                        go(groups[(gIdx + 1) % groups.length].items[0].el);
                        e.stopPropagation(); e.preventDefault();

                    } else if (key === 'ArrowLeft') {
                        // Jump to last item of previous group (wraps around).
                        var pg = groups[(gIdx - 1 + groups.length) % groups.length];
                        go(pg.items[pg.items.length - 1].el);
                        e.stopPropagation(); e.preventDefault();

                    } else {
                        // Up / Down: navigate within the current group.
                        var iIdx = itemOf(g);
                        if (iIdx === -1) {
                            go(g.items[0].el); e.stopPropagation(); e.preventDefault(); return;
                        }
                        var ni = key === 'ArrowDown' ? iIdx + 1 : iIdx - 1;
                        if (ni >= 0 && ni < g.items.length) {
                            go(g.items[ni].el);
                            e.stopPropagation(); e.preventDefault();
                        }
                        // At group boundary: leave event unconsumed so the page can scroll.
                    }
                }, true);

                // ── Auto-focus after SPA navigation (requirement d) ──────────────────

                // When HA navigates to a new dashboard page (pushState / popstate),
                // wait for the new content to render then focus the first non-sidebar item.
                function focusFirstContentItem() {
                    setTimeout(function () {
                        var items  = collectItems();
                        var groups = buildGroups(items);
                        // Sidebar groups sit in the leftmost ~25 % of the viewport; skip them.
                        var contentX = window.innerWidth * 0.25;
                        for (var i = 0; i < groups.length; i++) {
                            if (groups[i].cx > contentX) { go(groups[i].items[0].el); return; }
                        }
                        if (groups.length) go(groups[0].items[0].el);
                    }, SPA_RENDER_DELAY_MS);
                }

                window.addEventListener('popstate', focusFirstContentItem);
                try {
                    var origPush = history.pushState.bind(history);
                    history.pushState = function () { origPush.apply(history, arguments); focusFirstContentItem(); };
                } catch (ignored) {}

            })();
        """.trimIndent()

        evaluateJavascript(script, null)
    }

    companion object {
        fun getView(context: Context): CustomWebView {
            return try {
                CustomWebView(context)
            } catch (e: NotFoundException) {
                CustomWebView(context.applicationContext)
            }
        }
    }
}
