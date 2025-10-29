package com.taygames.flipperfish;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.getcapacitor.BridgeActivity;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;

public class MainActivity extends BridgeActivity {

    private WebView webView;
    private AdView bannerView;
    private RewardedAd rewardedAd;
    private boolean pendingReward = false;

    private static final String TEST_BANNER_ID = "ca-app-pub-6654580130821527/2832775997";
    private static final String TEST_REWARDED_ID = "ca-app-pub-6654580130821527/1411883119";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        MobileAds.initialize(this, status -> {});
        webView = this.bridge.getWebView();

        if (webView != null) {
            webView.addJavascriptInterface(new AdsBridge(this), "AdsBridge");
        }

        // Banner setup (adaptive size)
        bannerView = new AdView(this);
        bannerView.setAdUnitId(TEST_BANNER_ID);
        bannerView.setVisibility(View.GONE);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int adWidthPx = dm.widthPixels;
        int adWidthDp = Math.max(320, Math.round(adWidthPx / dm.density));
        AdSize adaptive = AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(this, adWidthDp);
        bannerView.setAdSize(adaptive);

        FrameLayout.LayoutParams bannerLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        addContentView(bannerView, bannerLp);
        bannerView.bringToFront();
        bannerView.setElevation(10f);

        AdRequest req = new AdRequest.Builder().build();
        bannerView.loadAd(req);

        bannerView.setAdListener(new AdListener() {
            @Override
            public void onAdFailedToLoad(@NonNull LoadAdError error) {
                Log.e("AdsBridge", "Banner load fail: " + error.getMessage());
            }
        });

        loadRewarded();
    }

    private void loadRewarded() {
        RewardedAd.load(this, TEST_REWARDED_ID, new AdRequest.Builder().build(),
                new RewardedAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull RewardedAd ad) {
                        rewardedAd = ad;
                    }

                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                        rewardedAd = null;
                        Log.e("AdsBridge", "Reward failed load: " + loadAdError.getMessage());
                    }
                });
    }

    private void jsCallback(String js) {
        if (webView == null) return;
        webView.post(() -> webView.evaluateJavascript(js, null));
    }

    @Override
    public void onPause() {
        super.onPause();
        if (bannerView != null) bannerView.pause();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (bannerView != null) bannerView.resume();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (bannerView != null) bannerView.destroy();
        rewardedAd = null;
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (webView != null) {
            webView.evaluateJavascript("if (typeof placeBanner === 'function') placeBanner();", null);
        }
    }

    public class AdsBridge {
        private final Activity activity;

        public AdsBridge(Activity act) {
            this.activity = act;
        }

        @JavascriptInterface
        public void showBanner() {
            activity.runOnUiThread(() -> bannerView.setVisibility(View.VISIBLE));
        }

        @JavascriptInterface
        public void hideBanner() {
            activity.runOnUiThread(() -> bannerView.setVisibility(View.GONE));
        }

        @JavascriptInterface
        public void positionBanner(final float x, final float y, final float width, final float height) {
            activity.runOnUiThread(() -> {
                int[] loc = new int[2];
                webView.getLocationOnScreen(loc);
                int webLeft = loc[0];
                int webTop = loc[1];

                int adW = bannerView.getAdSize().getWidthInPixels(MainActivity.this);
                int adH = bannerView.getAdSize().getHeightInPixels(MainActivity.this);

                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bannerView.getLayoutParams();
                lp.gravity = Gravity.TOP | Gravity.START;
                lp.leftMargin = Math.max(0, webLeft + Math.round(x) + (Math.round(width) - adW) / 2);
                lp.topMargin = Math.max(0, webTop + Math.round(y) + (Math.round(height) - adH) / 2);
                bannerView.setLayoutParams(lp);

                bannerView.setVisibility(View.VISIBLE);
                bannerView.bringToFront();
                bannerView.setElevation(10f);
            });
        }

        @JavascriptInterface
        public void preloadRewarded() {
            activity.runOnUiThread(() -> {
                if (rewardedAd == null) loadRewarded();
            });
        }

        @JavascriptInterface
        public void showRewardedForRevive() {
            activity.runOnUiThread(() -> {
                if (rewardedAd == null) {
                    jsCallback("window.onRewardedNotAvailable && window.onRewardedNotAvailable()");
                    loadRewarded();
                    return;
                }

                pendingReward = false;

                rewardedAd.setFullScreenContentCallback(new FullScreenContentCallback() {
                    @Override
                    public void onAdDismissedFullScreenContent() {
                        if (pendingReward) {
                            jsCallback("window.onRewardEarned && window.onRewardEarned()");
                        } else {
                            jsCallback("window.onRewardedNotAvailable && window.onRewardedNotAvailable()");
                        }
                        rewardedAd = null;
                        loadRewarded();
                    }
                });

                rewardedAd.show(MainActivity.this, rewardItem -> pendingReward = true);
            });
        }
    }
}
