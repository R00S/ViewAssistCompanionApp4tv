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
                // Set the guard flag immediately so that any re-entrant call (e.g. a
                // second onPageFinished firing before the first script completes) returns
                // before reaching the initialisation code below.
                if (window.__vaDpadNavigationInstalled) return;
                window.__vaDpadNavigationInstalled = true;

                var FOCUSABLE_SELECTOR = [
                    'a[href]', 'button', 'input', 'select', 'textarea',
                    '[tabindex]:not([tabindex="-1"])',
                    '[role="button"]', '[role="link"]', '[role="checkbox"]',
                    '[role="tab"]', '[role="menuitem"]', '[role="switch"]',
                    '[contenteditable="true"]'
                ].join(',');

                // Minimum px the candidate centre must be past the active centre in the
                // pressed direction before it qualifies as a directional neighbour.
                var DIRECTION_THRESHOLD = 5;

                // Weight applied to off-axis misalignment in the scoring formula:
                //   score = primaryDist + secondaryDist * SECONDARY_WEIGHT
                // Higher values favour well-aligned candidates over closer off-axis ones.
                var SECONDARY_WEIGHT = 2;

                // Milliseconds to wait after a SPA navigation before re-focusing, giving
                // Home Assistant time to render the new page content.
                var SPA_RENDER_DELAY_MS = 1000;

                // ── DOM helpers ──────────────────────────────────────────────────────

                // Walk upward through shadow boundaries:
                // ShadowRoot.parentNode is null, but ShadowRoot.host gives the host element.
                function composedParent(n) { return n.parentNode || n.host || null; }

                // Returns true if `ancestor` is a strict composed-DOM ancestor of `el`.
                function composedContains(ancestor, el) {
                    var cur = composedParent(el);
                    while (cur) {
                        if (cur === ancestor) return true;
                        cur = composedParent(cur);
                    }
                    return false;
                }

                // Returns the deepest focused element, piercing shadow roots.
                function deepActive() {
                    var el = document.activeElement;
                    while (el && el.shadowRoot && el.shadowRoot.activeElement) {
                        el = el.shadowRoot.activeElement;
                    }
                    return el;
                }

                // An element is visible when it is not hidden via CSS and has a non-zero
                // bounding rect.  We intentionally do NOT use offsetParent because shadow-DOM
                // elements often have a null offsetParent even when fully visible.
                // Returns the rect on success (truthy) or null when hidden, so callers can
                // reuse the already-computed rect and avoid a second layout query.
                function visibleRect(el) {
                    if (!el || el.disabled) return null;
                    if (el.getAttribute && el.getAttribute('aria-hidden') === 'true') return null;
                    var s = window.getComputedStyle(el);
                    if (s.display === 'none' || s.visibility === 'hidden') return null;
                    var r = el.getBoundingClientRect();
                    return (r.width > 0 && r.height > 0) ? r : null;
                }

                // ── Item collection ──────────────────────────────────────────────────

                // Returns [{el, rect, cx, cy}] for all visible focusable elements,
                // piercing every shadow root in the document tree, then removes any
                // element that is a composed-DOM ancestor of another collected element.
                // This prevents focusable container wrappers (e.g. ha-sidebar, ha-card)
                // from obscuring the actual interactive targets inside them.
                //
                // Performance notes:
                //  • visibleRect() is called once per element during collection; the
                //    returned rect is cached in the item object so findBest() never
                //    triggers an additional layout recalculation.
                //  • The ancestor-exclusion filter builds a Set of ancestors in O(n·d)
                //    (d = average DOM depth) before the filter pass, avoiding the O(n²)
                //    cost of running composedContains inside the filter loop.
                function collectItems() {
                    var seen = [], raw = [];
                    function collect(root) {
                        var m = root.querySelectorAll(FOCUSABLE_SELECTOR);
                        for (var i = 0; i < m.length; i++) {
                            if (seen.indexOf(m[i]) === -1) {
                                var r = visibleRect(m[i]);
                                if (r) { seen.push(m[i]); raw.push({ el: m[i], rect: r }); }
                            }
                        }
                        var all = root.querySelectorAll('*');
                        for (var j = 0; j < all.length; j++) {
                            if (all[j].shadowRoot) collect(all[j].shadowRoot);
                        }
                    }
                    collect(document);

                    var items = raw.map(function (item) {
                        var r = item.rect;
                        return { el: item.el, rect: r, cx: r.left + r.width / 2, cy: r.top + r.height / 2 };
                    });

                    // Build a set of elements that are ancestors of at least one other item.
                    // An ancestor-element should not appear as a navigation target itself
                    // (e.g. ha-sidebar should not be selected when its child links are present).
                    var ancestorSet = [];
                    for (var a = 0; a < items.length; a++) {
                        var cur = composedParent(items[a].el);
                        while (cur) {
                            if (ancestorSet.indexOf(cur) === -1) ancestorSet.push(cur);
                            cur = composedParent(cur);
                        }
                    }
                    return items.filter(function (item) {
                        return ancestorSet.indexOf(item.el) === -1;
                    });
                }

                // ── Spatial navigation ───────────────────────────────────────────────

                // Find the best candidate in the given arrow direction using a 2D spatial
                // scoring formula: score = primaryDist + secondaryDist * SECONDARY_WEIGHT.
                // All four arrow keys work freely across the entire page, allowing movement
                // from the sidebar to cards, from cards to the top tab bar, and so on.
                function findBest(key, activeItem, items) {
                    var ax = activeItem ? activeItem.cx : -9999;
                    var ay = activeItem ? activeItem.cy : -9999;
                    var best = null, bestScore = Infinity;

                    for (var i = 0; i < items.length; i++) {
                        var it = items[i];
                        if (activeItem && it.el === activeItem.el) continue;

                        var primary = 0, secondary = 0, inDir = false;

                        if (key === 'ArrowRight') {
                            inDir    = it.cx > ax + DIRECTION_THRESHOLD;
                            primary  = it.cx - ax;
                            secondary = Math.abs(it.cy - ay);
                        } else if (key === 'ArrowLeft') {
                            inDir    = it.cx < ax - DIRECTION_THRESHOLD;
                            primary  = ax - it.cx;
                            secondary = Math.abs(it.cy - ay);
                        } else if (key === 'ArrowDown') {
                            inDir    = it.cy > ay + DIRECTION_THRESHOLD;
                            primary  = it.cy - ay;
                            secondary = Math.abs(it.cx - ax);
                        } else if (key === 'ArrowUp') {
                            inDir    = it.cy < ay - DIRECTION_THRESHOLD;
                            primary  = ay - it.cy;
                            secondary = Math.abs(it.cx - ax);
                        }

                        if (!inDir) continue;
                        var score = primary + secondary * SECONDARY_WEIGHT;
                        if (score < bestScore) { bestScore = score; best = it; }
                    }
                    return best;
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

                // Capture phase (true) so our handler runs before any shadow-DOM component
                // handlers.  stopPropagation is called whenever we move focus so that HA
                // components cannot intercept and steal focus back.  When no neighbour
                // exists in the pressed direction we leave the event unconsumed so the
                // browser can still scroll the page naturally.
                document.addEventListener('keydown', function (e) {
                    if (e.defaultPrevented) return;
                    var key = e.key;
                    if (key !== 'ArrowUp' && key !== 'ArrowDown' &&
                            key !== 'ArrowLeft' && key !== 'ArrowRight') return;

                    var items = collectItems();
                    if (!items.length) return;

                    var active = deepActive();
                    var activeItem = null;
                    for (var i = 0; i < items.length; i++) {
                        if (items[i].el === active) { activeItem = items[i]; break; }
                    }

                    if (!activeItem) {
                        // No recognised focus → place focus on the first content item.
                        go(items[0].el);
                        e.stopPropagation(); e.preventDefault();
                        return;
                    }

                    var target = findBest(key, activeItem, items);
                    if (target) {
                        go(target.el);
                        e.stopPropagation(); e.preventDefault();
                    }
                    // At a spatial edge: don't consume so native scroll still works.
                }, true);

                // ── Auto-focus after SPA navigation ──────────────────────────────────

                // When HA navigates to a new dashboard page (pushState / popstate),
                // wait SPA_RENDER_DELAY_MS for the new content to render, then place
                // focus on the first item that is not in the sidebar (leftmost 25 % of
                // the viewport).
                function focusFirstContentItem() {
                    setTimeout(function () {
                        var items = collectItems();
                        if (!items.length) return;
                        var contentX = window.innerWidth * 0.25;
                        for (var i = 0; i < items.length; i++) {
                            if (items[i].cx > contentX) { go(items[i].el); return; }
                        }
                        go(items[0].el);
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
