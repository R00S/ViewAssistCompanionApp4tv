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

                function getFocusableElements() {
                    var selector = [
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
                    return Array.prototype.slice.call(document.querySelectorAll(selector)).filter(function (el) {
                        if (!el || el.disabled) return false;
                        if (el.getAttribute('aria-hidden') === 'true') return false;
                        var style = window.getComputedStyle(el);
                        if (style.display === 'none' || style.visibility === 'hidden') return false;
                        return el.offsetParent !== null || style.position === 'fixed';
                    });
                }

                function moveFocus(forward) {
                    var elements = getFocusableElements();
                    if (elements.length === 0) return false;

                    var active = document.activeElement;
                    var index = elements.indexOf(active);
                    var target;

                    if (index === -1) {
                        target = forward ? elements[0] : elements[elements.length - 1];
                    } else {
                        var next = forward
                            ? (index + 1) % elements.length
                            : (index - 1 + elements.length) % elements.length;
                        target = elements[next];
                    }

                    if (!target) return false;
                    target.focus();
                    if (typeof target.scrollIntoView === 'function') {
                        target.scrollIntoView({block: 'nearest', inline: 'nearest'});
                    }
                    return true;
                }

                document.addEventListener('keydown', function (event) {
                    if (event.defaultPrevented) return;
                    var key = event.key;
                    var arrowKeys = ['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'];
                    if (arrowKeys.indexOf(key) === -1) {
                        return;
                    }

                    var handled = (key === 'ArrowUp' || key === 'ArrowLeft')
                        ? moveFocus(false)
                        : moveFocus(true);

                    if (handled) {
                        event.preventDefault();
                        event.stopPropagation();
                    }
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
