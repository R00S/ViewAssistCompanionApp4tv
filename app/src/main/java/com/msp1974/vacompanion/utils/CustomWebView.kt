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
                if (window.__vaDpadNavigationInstalled) {
                    return;
                }
                window.__vaDpadNavigationInstalled = true;

                // Minimum pixel offset for an element to be considered "in" a direction.
                // Prevents items at the same grid line from being treated as directional candidates.
                var DIRECTION_THRESHOLD = 5;

                // Weight applied to secondary-axis misalignment in the scoring formula
                // (score = primaryDist + secondaryDist * SECONDARY_AXIS_WEIGHT).
                // A value of 2 means a perfect secondary-axis alignment is preferred over
                // a slightly closer but off-axis element.
                var SECONDARY_AXIS_WEIGHT = 2;

                // Sentinel used when no active element is present; guarantees every visible
                // element passes the directional filter on the first key press.
                var NO_ACTIVE_POSITION = -9999;

                var FOCUSABLE_SELECTOR = [
                    'a[href]',
                    'button',
                    'input',
                    'select',
                    'textarea',
                    '[tabindex]:not([tabindex="-1"])',
                    '[role="button"]',
                    '[role="link"]',
                    '[role="checkbox"]',
                    '[role="tab"]',
                    '[role="menuitem"]',
                    '[role="switch"]',
                    '[contenteditable="true"]'
                ].join(',');

                function isVisible(el) {
                    if (!el || el.disabled) return false;
                    if (el.getAttribute('aria-hidden') === 'true') return false;
                    var style = window.getComputedStyle(el);
                    if (style.display === 'none' || style.visibility === 'hidden') return false;
                    if (!(el.offsetParent !== null || style.position === 'fixed')) return false;
                    return true;
                }

                // Returns an array of {el, rect, cx, cy} objects for every visible
                // focusable element, with bounding rects read in a single pass so later
                // callers never trigger additional synchronous layout recalculations.
                function getFocusableItems() {
                    var raw = [];

                    function collectFromRoot(root) {
                        var matches = root.querySelectorAll(FOCUSABLE_SELECTOR);
                        for (var i = 0; i < matches.length; i++) {
                            if (isVisible(matches[i])) raw.push(matches[i]);
                        }
                        var allNodes = root.querySelectorAll('*');
                        for (var j = 0; j < allNodes.length; j++) {
                            var node = allNodes[j];
                            if (node && node.shadowRoot) {
                                collectFromRoot(node.shadowRoot);
                            }
                        }
                    }

                    collectFromRoot(document);

                    // Deduplicate
                    var seen = [];
                    var deduped = raw.filter(function (el) {
                        if (seen.indexOf(el) === -1) { seen.push(el); return true; }
                        return false;
                    });

                    // Read all bounding rects in one pass to avoid repeated layout queries.
                    var items = deduped.map(function (el) {
                        var r = el.getBoundingClientRect();
                        return { el: el, rect: r, cx: r.left + r.width / 2, cy: r.top + r.height / 2 };
                    });

                    // Exclude container elements whose bounding rect fully contains another
                    // focusable element. This prevents focus from getting trapped on a focusable
                    // panel host (e.g. ha-sidebar) that wraps its own navigable items (Bug 1).
                    return items.filter(function (item) {
                        var r = item.rect;
                        if (r.width === 0 && r.height === 0) return true;
                        for (var k = 0; k < items.length; k++) {
                            if (items[k] === item) continue;
                            var c = items[k].rect;
                            if (c.width > 0 && c.height > 0 &&
                                    c.left >= r.left && c.right <= r.right &&
                                    c.top >= r.top && c.bottom <= r.bottom) {
                                return false;
                            }
                        }
                        return true;
                    });
                }

                function getDeepActiveElement() {
                    var active = document.activeElement;
                    while (active && active.shadowRoot && active.shadowRoot.activeElement) {
                        active = active.shadowRoot.activeElement;
                    }
                    return active;
                }

                // Spatial (2-D) navigation: find the item whose bounding-rect centre lies
                // closest in the pressed arrow direction. Uses pre-computed cx/cy to avoid
                // redundant layout queries.
                // Primary-axis distance drives the score; secondary-axis distance
                // (alignment) is a tie-breaker weighted by SECONDARY_AXIS_WEIGHT.
                function findBestInDirection(key, activeItem, items) {
                    var ax = activeItem ? activeItem.cx : NO_ACTIVE_POSITION;
                    var ay = activeItem ? activeItem.cy : NO_ACTIVE_POSITION;

                    var best = null;
                    var bestScore = Infinity;

                    for (var i = 0; i < items.length; i++) {
                        var item = items[i];
                        if (activeItem && item.el === activeItem.el) continue;
                        if (item.rect.width === 0 && item.rect.height === 0) continue;

                        var inDirection = false;
                        var primaryDist = 0, secondaryDist = 0;

                        if (key === 'ArrowRight') {
                            inDirection = item.cx > ax + DIRECTION_THRESHOLD;
                            primaryDist = item.cx - ax;
                            secondaryDist = Math.abs(item.cy - ay);
                        } else if (key === 'ArrowLeft') {
                            inDirection = item.cx < ax - DIRECTION_THRESHOLD;
                            primaryDist = ax - item.cx;
                            secondaryDist = Math.abs(item.cy - ay);
                        } else if (key === 'ArrowDown') {
                            inDirection = item.cy > ay + DIRECTION_THRESHOLD;
                            primaryDist = item.cy - ay;
                            secondaryDist = Math.abs(item.cx - ax);
                        } else if (key === 'ArrowUp') {
                            inDirection = item.cy < ay - DIRECTION_THRESHOLD;
                            primaryDist = ay - item.cy;
                            secondaryDist = Math.abs(item.cx - ax);
                        }

                        if (!inDirection) continue;
                        var score = primaryDist + secondaryDist * SECONDARY_AXIS_WEIGHT;
                        if (score < bestScore) {
                            bestScore = score;
                            best = item;
                        }
                    }
                    return best ? best.el : null;
                }

                // Capture phase (true) ensures we see the event before shadow-DOM components do,
                // but we deliberately omit stopPropagation so those components can still react
                // to the same keydown (e.g. HA dropdowns, sliders, and other interactive widgets).
                document.addEventListener('keydown', function (event) {
                    if (event.defaultPrevented) return;
                    var key = event.key;
                    if (key !== 'ArrowUp' && key !== 'ArrowDown' &&
                            key !== 'ArrowLeft' && key !== 'ArrowRight') return;

                    var items = getFocusableItems();
                    if (items.length === 0) return;

                    var active = getDeepActiveElement();
                    var activeItem = null;
                    for (var i = 0; i < items.length; i++) {
                        if (items[i].el === active) { activeItem = items[i]; break; }
                    }

                    var target = findBestInDirection(key, activeItem, items);

                    // Nothing found spatially – if focus is also absent from our list
                    // (e.g. page body focused at startup), jump to the first element.
                    if (!target && !activeItem) {
                        target = items[0].el;
                    }

                    if (target) {
                        target.focus();
                        if (typeof target.scrollIntoView === 'function') {
                            target.scrollIntoView({ block: 'nearest', inline: 'nearest' });
                        }
                        event.preventDefault();
                    }
                    // At a spatial edge with a known focused element: don't consume the
                    // event so HA components can handle any remaining default behaviour.
                }, true);
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
