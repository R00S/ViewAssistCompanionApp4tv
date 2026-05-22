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
        // D-pad navigation using a doubly-linked list of GUI element groups.
        //
        // The HA UI is divided into three natural zones that this scheme handles:
        //   • Sidebar  – left panel, itself split into several sub-elements
        //                (top menu, navigation list, bottom settings …)
        //   • Views    – the tab/view switcher bar at the top of the main area
        //   • Main area – individual cards, sections, dialogs
        //
        // Each zone contains multiple GUI elements (nodes in the linked list).
        // Each node contains an ordered list of entries (focusable items).
        //
        // Navigation rules (as designed by the user):
        //   Down  – next entry within the current GUI element (wraps)
        //   Up    – previous entry within the current GUI element (wraps)
        //   Right – first entry in the next GUI element
        //   Left  – last entry in the previous GUI element
        //   Click / Enter / Space – rebuild the list; if a new GUI element
        //           appeared (e.g. a dialog opened), focus its first entry
        val script = """
            (function () {
                if (window.__vaDpadNavigationInstalled) return;
                window.__vaDpadNavigationInstalled = true;

                // ── Constants ─────────────────────────────────────────────────────────

                var FOCUSABLE_SELECTOR = [
                    'a[href]', 'button', 'input', 'select', 'textarea',
                    '[tabindex]:not([tabindex="-1"])',
                    '[role="button"]', '[role="link"]', '[role="checkbox"]',
                    '[role="tab"]', '[role="menuitem"]', '[role="switch"]',
                    '[contenteditable="true"]'
                ].join(',');

                // A group anchor is only valid when its bounding rect is smaller than
                // this fraction of the viewport in at least one dimension.  This stops
                // the top-level app-shell from swallowing all items into one group.
                var MAX_GROUP_RATIO = 0.85;

                // Pixel band used when sorting items / groups into reading-order rows.
                var ROW_BAND_PX = 40;

                // After a click / Enter / SPA-navigation, wait this long for HA to
                // finish rendering before rebuilding the group list.
                var RENDER_DELAY_MS = 500;

                // ── DOM helpers ───────────────────────────────────────────────────────

                // Walk up through shadow-root boundaries.
                // ShadowRoot (nodeType 11) has no parentNode but has a .host element.
                function composedParent(n) {
                    return n.parentNode || (n.nodeType === 11 ? n.host : null);
                }

                // Deepest focused element, piercing nested shadow roots.
                function deepActive() {
                    var el = document.activeElement;
                    while (el && el.shadowRoot && el.shadowRoot.activeElement) {
                        el = el.shadowRoot.activeElement;
                    }
                    return el;
                }

                function isVisible(el) {
                    if (!el || el.disabled) return false;
                    if (el.getAttribute && el.getAttribute('aria-hidden') === 'true') return false;
                    var s = window.getComputedStyle(el);
                    if (s.display === 'none' || s.visibility === 'hidden') return false;
                    var r = el.getBoundingClientRect();
                    return r.width > 0 && r.height > 0;
                }

                // ── Item collection ───────────────────────────────────────────────────

                // Returns [{el, rect, cx, cy}] for every visible focusable element,
                // piercing every shadow root.  Focusable container wrappers (ha-sidebar,
                // ha-card …) that are composed-DOM ancestors of other collected elements
                // are then removed so they never crowd out the real targets inside them.
                function collectAll() {
                    var seen = [], raw = [];
                    function collect(root) {
                        var m = root.querySelectorAll(FOCUSABLE_SELECTOR);
                        for (var i = 0; i < m.length; i++) {
                            if (seen.indexOf(m[i]) === -1 && isVisible(m[i])) {
                                seen.push(m[i]);
                                var r = m[i].getBoundingClientRect();
                                raw.push({ el: m[i], rect: r,
                                           cx: r.left + r.width  / 2,
                                           cy: r.top  + r.height / 2 });
                            }
                        }
                        var all = root.querySelectorAll('*');
                        for (var j = 0; j < all.length; j++) {
                            if (all[j].shadowRoot) collect(all[j].shadowRoot);
                        }
                    }
                    collect(document);

                    var elems = raw.map(function (it) { return it.el; });
                    return raw.filter(function (it) {
                        var cur = composedParent(it.el);
                        while (cur) {
                            if (elems.indexOf(cur) !== -1) return false;
                            cur = composedParent(cur);
                        }
                        return true;
                    });
                }

                // ── Group-anchor detection ────────────────────────────────────────────

                // Walk up the composed DOM from el and return the smallest ancestor that:
                //   (a) fits within MAX_GROUP_RATIO of the viewport in at least one axis,
                //   (b) strictly encloses at least 2 of the collected items.
                // Falls back to el itself so every item always belongs to a group.
                //
                // This naturally discovers the three HA UI zones and their sub-elements:
                //   Sidebar  → sidebar-top section, nav-list, bottom section, …
                //   Views    → the tab-bar container
                //   Main area → individual cards, expanded sections, dialogs, …
                function groupAnchor(el, items) {
                    var vpW = window.innerWidth;
                    var vpH = window.innerHeight;
                    var cur = composedParent(el);
                    while (cur && cur !== document && cur !== document.documentElement) {
                        if (typeof cur.getBoundingClientRect === 'function') {
                            var r = cur.getBoundingClientRect();
                            if (r.width > 0 && r.height > 0) {
                                // Stop climbing once the ancestor spans nearly the full
                                // viewport in both directions – too coarse to be useful.
                                if (r.width  > vpW * MAX_GROUP_RATIO &&
                                        r.height > vpH * MAX_GROUP_RATIO) break;
                                var n = 0;
                                for (var k = 0; k < items.length; k++) {
                                    var ir = items[k].rect;
                                    if (ir.left + 1 >= r.left && ir.right  - 1 <= r.right &&
                                            ir.top  + 1 >= r.top  && ir.bottom - 1 <= r.bottom) {
                                        if (++n >= 2) return cur;
                                    }
                                }
                            }
                        }
                        cur = composedParent(cur);
                    }
                    return el;   // element is its own single-entry group
                }

                // ── Doubly-linked list construction ───────────────────────────────────

                // Returns an array of linked nodes sorted in reading order.
                // Each node: { items: [{el,rect,cx,cy}…], prev: node|null, next: node|null }
                function buildList(items) {
                    if (!items.length) return [];

                    // Assign every item to its group anchor.
                    var anchors = [], buckets = [];
                    for (var i = 0; i < items.length; i++) {
                        var a = groupAnchor(items[i].el, items);
                        var idx = anchors.indexOf(a);
                        if (idx === -1) { anchors.push(a); buckets.push([items[i]]); }
                        else            { buckets[idx].push(items[i]); }
                    }

                    // Sort entries within each group in reading order (top→bottom, left→right).
                    for (var b = 0; b < buckets.length; b++) {
                        buckets[b].sort(function (x, y) {
                            if (Math.abs(x.cy - y.cy) < ROW_BAND_PX) return x.cx - y.cx;
                            return x.cy - y.cy;
                        });
                    }

                    // Sort groups in reading order using their top-left origin.
                    var grouped = buckets.map(function (its) {
                        var top = Infinity, left = Infinity;
                        for (var i = 0; i < its.length; i++) {
                            if (its[i].rect.top  < top)  top  = its[i].rect.top;
                            if (its[i].rect.left < left) left = its[i].rect.left;
                        }
                        return { items: its, top: top, left: left };
                    });
                    grouped.sort(function (x, y) {
                        if (Math.abs(x.top - y.top) < ROW_BAND_PX) return x.left - y.left;
                        return x.top - y.top;
                    });

                    // Allocate nodes and wire prev/next pointers.
                    var nodes = grouped.map(function (g) {
                        return { items: g.items, prev: null, next: null };
                    });
                    for (var i = 0; i < nodes.length; i++) {
                        if (i > 0)                nodes[i].prev = nodes[i - 1];
                        if (i < nodes.length - 1) nodes[i].next = nodes[i + 1];
                    }
                    return nodes;
                }

                // ── Navigation state ──────────────────────────────────────────────────

                var nodes   = [];    // flat array of all nodes (for searching)
                var curNode = null;  // currently active node
                var curIdx  = 0;     // active entry index within curNode

                // Sync curNode/curIdx to whichever element is currently focused in the DOM.
                function syncToActive() {
                    var active = deepActive();
                    if (!active) return;
                    for (var n = 0; n < nodes.length; n++) {
                        for (var i = 0; i < nodes[n].items.length; i++) {
                            if (nodes[n].items[i].el === active) {
                                curNode = nodes[n]; curIdx = i; return;
                            }
                        }
                    }
                }

                function rebuildAndSync() {
                    nodes = buildList(collectAll());
                    curNode = nodes.length ? nodes[0] : null;
                    curIdx  = 0;
                    syncToActive();
                }

                function doFocus(node, idx) {
                    if (!node || !node.items.length) return false;
                    idx = Math.max(0, Math.min(idx, node.items.length - 1));
                    var el = node.items[idx].el;
                    try {
                        el.focus();
                        if (el.scrollIntoView) el.scrollIntoView({ block: 'nearest', inline: 'nearest' });
                        curNode = node;
                        curIdx  = idx;
                        return true;
                    } catch (err) { return false; }
                }

                // ── Arrow-key handler (capture phase) ─────────────────────────────────

                document.addEventListener('keydown', function (e) {
                    if (e.defaultPrevented) return;
                    var key = e.key;
                    if (key !== 'ArrowUp' && key !== 'ArrowDown' &&
                            key !== 'ArrowLeft' && key !== 'ArrowRight') return;

                    if (!nodes.length) rebuildAndSync();
                    if (!nodes.length) return;

                    // Re-sync whenever mouse/touch focus moved between key presses.
                    if (!curNode || !curNode.items[curIdx] ||
                            curNode.items[curIdx].el !== deepActive()) {
                        syncToActive();
                    }
                    if (!curNode) { curNode = nodes[0]; curIdx = 0; }

                    var moved = false;

                    if (key === 'ArrowDown') {
                        // Next entry within the current GUI element (wraps).
                        moved = doFocus(curNode, (curIdx + 1) % curNode.items.length);

                    } else if (key === 'ArrowUp') {
                        // Previous entry within the current GUI element (wraps).
                        moved = doFocus(curNode,
                            (curIdx - 1 + curNode.items.length) % curNode.items.length);

                    } else if (key === 'ArrowRight') {
                        // First entry in the next GUI element (no wrap at end).
                        if (curNode.next) moved = doFocus(curNode.next, 0);

                    } else if (key === 'ArrowLeft') {
                        // Last entry in the previous GUI element (no wrap at start).
                        if (curNode.prev)
                            moved = doFocus(curNode.prev, curNode.prev.items.length - 1);
                    }

                    // Prevent default scroll only when we actually moved focus.
                    // NOT calling stopPropagation so HA's own component handlers
                    // (sliders, dropdowns, dialogs …) can still react to key events.
                    if (moved) e.preventDefault();
                }, true);

                // ── Post-action rebuild ───────────────────────────────────────────────

                // After a click, Enter, or Space, HA may open a dialog, expand a
                // section, or navigate within the SPA.  Snapshot the current element
                // set NOW (before the action renders), then after RENDER_DELAY_MS:
                //   • If a brand-new GUI element appeared → focus its first entry.
                //   • Otherwise → stay wherever the DOM left focus.
                var rebuildTimer = null;

                function scheduleRebuild() {
                    // Snapshot BEFORE the action takes effect.
                    var prevEls = [];
                    for (var n = 0; n < nodes.length; n++) {
                        for (var i = 0; i < nodes[n].items.length; i++) {
                            prevEls.push(nodes[n].items[i].el);
                        }
                    }
                    if (rebuildTimer !== null) clearTimeout(rebuildTimer);
                    rebuildTimer = setTimeout(function () {
                        rebuildTimer = null;
                        var newNodes = buildList(collectAll());
                        nodes = newNodes;
                        if (!newNodes.length) { curNode = null; curIdx = 0; return; }

                        // Find the first node whose entries are all brand-new.
                        for (var n = 0; n < newNodes.length; n++) {
                            var allNew = true;
                            for (var i = 0; i < newNodes[n].items.length; i++) {
                                if (prevEls.indexOf(newNodes[n].items[i].el) !== -1) {
                                    allNew = false; break;
                                }
                            }
                            if (allNew) { doFocus(newNodes[n], 0); return; }
                        }
                        // No new GUI element: sync to wherever focus landed.
                        syncToActive();
                    }, RENDER_DELAY_MS);
                }

                // Bubble phase: the click/key action fires first, we rebuild after.
                document.addEventListener('click', scheduleRebuild, false);
                document.addEventListener('keydown', function (e) {
                    if (e.key === 'Enter' || e.key === ' ') scheduleRebuild();
                }, false);

                // ── SPA navigation ────────────────────────────────────────────────────

                function onSpaNavigate() {
                    if (rebuildTimer !== null) clearTimeout(rebuildTimer);
                    rebuildTimer = setTimeout(function () {
                        rebuildTimer = null;
                        rebuildAndSync();
                        if (curNode) doFocus(curNode, 0);
                    }, RENDER_DELAY_MS + 500);
                }

                window.addEventListener('popstate', onSpaNavigate);
                try {
                    var origPush = history.pushState.bind(history);
                    history.pushState = function () {
                        origPush.apply(history, arguments); onSpaNavigate();
                    };
                } catch (ignored) {}

                // ── Initial build ─────────────────────────────────────────────────────
                rebuildAndSync();
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
