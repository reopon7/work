package jp.ddo.pigsty.HabitBrowser.Component.Application;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Message;
import android.view.KeyEvent;
import android.webkit.ClientCertRequest;
import android.webkit.HttpAuthHandler;
import android.webkit.SafeBrowsingResponse;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.RenderProcessGoneDetail;
import android.net.http.SslError;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Blocks the two redirect/ad destinations that repeatedly hijack normal browsing.
 *
 * The legacy Habit WebViewClient is preserved as a delegate. On Android 8.0+
 * we obtain the currently installed client and wrap it, so existing navigation,
 * SSL/auth, history, request interception and page lifecycle behavior continue
 * to run.
 */
final class RedirectGuard {
    private RedirectGuard() {}

    static void install(WebView webView) {
        if (webView == null || Build.VERSION.SDK_INT < 26) return;

        try {
            Method getter = WebView.class.getMethod("getWebViewClient");
            Object currentObject = getter.invoke(webView);
            if (!(currentObject instanceof WebViewClient)) return;

            WebViewClient current = (WebViewClient) currentObject;
            if (current instanceof GuardClient) return;

            webView.setWebViewClient(new GuardClient(current));
        } catch (Throwable ignored) {
            // JavaScript guard still operates if native wrapping is unavailable.
        }
    }

    static boolean isBlocked(String rawUrl) {
        if (rawUrl == null) return false;

        String lower;
        try {
            lower = Uri.decode(rawUrl).toLowerCase(Locale.US);
        } catch (Throwable ignored) {
            lower = rawUrl.toLowerCase(Locale.US);
        }

        if (lower.startsWith("coupang:") ||
            lower.startsWith("aliexpress:")) {
            return true;
        }

        // App/deep-link redirects used by ad networks.
        if (lower.startsWith("intent:") ||
            lower.startsWith("market:")) {
            return lower.contains("coupang") ||
                   lower.contains("com.coupang.mobile") ||
                   lower.contains("aliexpress") ||
                   lower.contains("com.alibaba.aliexpresshd");
        }

        try {
            Uri uri = Uri.parse(rawUrl);
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.US);

            return hostMatches(host, "coupang.com") ||
                   host.equals("coupang.page.link") ||
                   hostMatches(host, "aliexpress.com") ||
                   hostMatches(host, "aliexpress.us") ||
                   hostMatches(host, "aliexpress.ru") ||
                   hostMatches(host, "aliexpress.kr") ||
                   host.equals("ali.pub") ||
                   host.endsWith(".ali.pub") ||
                   host.equals("ali.ski") ||
                   host.endsWith(".ali.ski");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hostMatches(String host, String root) {
        return host.equals(root) || host.endsWith("." + root);
    }

    private static WebResourceResponse blockedResponse() {
        return new WebResourceResponse(
                "text/plain",
                "UTF-8",
                new ByteArrayInputStream(new byte[0]));
    }

    private static final class GuardClient extends WebViewClient {
        private final WebViewClient delegate;

        GuardClient(WebViewClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            if (isBlocked(url)) return true;
            return delegate.shouldOverrideUrlLoading(view, url);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request != null && request.getUrl() != null
                    ? request.getUrl().toString() : null;
            if (isBlocked(url)) return true;
            return delegate.shouldOverrideUrlLoading(view, request);
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            if (isBlocked(url)) return blockedResponse();
            return delegate.shouldInterceptRequest(view, url);
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            String url = request != null && request.getUrl() != null
                    ? request.getUrl().toString() : null;
            if (isBlocked(url)) return blockedResponse();
            return delegate.shouldInterceptRequest(view, request);
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            delegate.onPageStarted(view, url, favicon);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            delegate.onPageFinished(view, url);
        }

        @Override
        public void onLoadResource(WebView view, String url) {
            delegate.onLoadResource(view, url);
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            if (Build.VERSION.SDK_INT >= 23) {
                delegate.onPageCommitVisible(view, url);
            }
        }

        @Override
        public void onReceivedError(
                WebView view, int errorCode, String description, String failingUrl) {
            delegate.onReceivedError(view, errorCode, description, failingUrl);
        }

        @Override
        public void onReceivedError(
                WebView view, WebResourceRequest request, WebResourceError error) {
            if (Build.VERSION.SDK_INT >= 23) {
                delegate.onReceivedError(view, request, error);
            }
        }

        @Override
        public void onReceivedHttpError(
                WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
            if (Build.VERSION.SDK_INT >= 23) {
                delegate.onReceivedHttpError(view, request, errorResponse);
            }
        }

        @Override
        public void onReceivedSslError(
                WebView view, SslErrorHandler handler, SslError error) {
            delegate.onReceivedSslError(view, handler, error);
        }

        @Override
        public void onReceivedClientCertRequest(WebView view, ClientCertRequest request) {
            if (Build.VERSION.SDK_INT >= 21) {
                delegate.onReceivedClientCertRequest(view, request);
            }
        }

        @Override
        public void onReceivedHttpAuthRequest(
                WebView view, HttpAuthHandler handler, String host, String realm) {
            delegate.onReceivedHttpAuthRequest(view, handler, host, realm);
        }

        @Override
        public void onFormResubmission(WebView view, Message dontResend, Message resend) {
            delegate.onFormResubmission(view, dontResend, resend);
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            delegate.doUpdateVisitedHistory(view, url, isReload);
        }

        @Override
        public boolean shouldOverrideKeyEvent(WebView view, KeyEvent event) {
            return delegate.shouldOverrideKeyEvent(view, event);
        }

        @Override
        public void onUnhandledKeyEvent(WebView view, KeyEvent event) {
            delegate.onUnhandledKeyEvent(view, event);
        }

        @Override
        public void onScaleChanged(WebView view, float oldScale, float newScale) {
            delegate.onScaleChanged(view, oldScale, newScale);
        }

        @Override
        public void onReceivedLoginRequest(
                WebView view, String realm, String account, String args) {
            if (Build.VERSION.SDK_INT >= 12) {
                delegate.onReceivedLoginRequest(view, realm, account, args);
            }
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            if (Build.VERSION.SDK_INT >= 26) {
                return delegate.onRenderProcessGone(view, detail);
            }
            return false;
        }

        @Override
        public void onSafeBrowsingHit(
                WebView view,
                WebResourceRequest request,
                int threatType,
                SafeBrowsingResponse callback) {
            if (Build.VERSION.SDK_INT >= 27) {
                delegate.onSafeBrowsingHit(view, request, threatType, callback);
            } else if (callback != null) {
                callback.backToSafety(true);
            }
        }
    }
}
