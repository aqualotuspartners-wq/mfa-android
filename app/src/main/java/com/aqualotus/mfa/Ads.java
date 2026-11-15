package com.aqualotus.mfa;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.os.SystemClock;
import android.widget.LinearLayout;
import com.google.android.gms.ads.*;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.ump.*;
import java.util.function.BooleanSupplier;

final class Ads {
    private final Activity activity;
    private final BooleanSupplier free;
    private final ConsentInformation consent;
    private final boolean debug;
    private boolean started;
    private boolean ready;
    private boolean loading;
    private long lastShown = -180000;
    private int guideVisits;
    private InterstitialAd interstitial;

    Ads(Activity activity, BooleanSupplier free) {
        this.activity = activity;
        this.free = free;
        debug = (activity.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        consent = UserMessagingPlatform.getConsentInformation(activity);
    }

    void start() {
        if (started || !free.getAsBoolean()) return;
        started = true;
        consent.requestConsentInfoUpdate(activity, new ConsentRequestParameters.Builder().build(),
            () -> {
                if (alive()) UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity, error -> initialize());
            }, error -> initialize());
    }

    private boolean alive() { return !activity.isDestroyed() && !activity.isFinishing(); }

    private boolean allowed() { return alive() && free.getAsBoolean() && consent.canRequestAds(); }

    private void initialize() {
        if (!allowed()) return;
        MobileAds.initialize(activity, ignored -> activity.runOnUiThread(() -> {
            ready = true;
            preload();
        }));
    }

    AdView banner(LinearLayout container, boolean transfer) {
        if (!ready || !allowed()) return null;
        AdView view = new AdView(activity);
        view.setAdSize(AdSize.BANNER);
        view.setAdUnitId(debug ? "ca-app-pub-3940256099942544/6300978111" : transfer
            ? "ca-app-pub-3956817331315524/9651673359" : "ca-app-pub-3956817331315524/3217951932");
        container.addView(view, new LinearLayout.LayoutParams(-2, -2));
        view.loadAd(new AdRequest.Builder().build());
        return view;
    }

    void preload() {
        if (!ready || !allowed() || loading || interstitial != null) return;
        loading = true;
        InterstitialAd.load(activity, debug ? "ca-app-pub-3940256099942544/1033173712"
            : "ca-app-pub-3956817331315524/5712428344",
            new AdRequest.Builder().build(), new InterstitialAdLoadCallback() {
                public void onAdLoaded(InterstitialAd ad) {
                    loading = false;
                    if (allowed()) interstitial = ad;
                }
                public void onAdFailedToLoad(LoadAdError error) { loading = false; }
            });
    }

    void guideFinished() {
        if (!allowed() || ++guideVisits < 2) return;
        long now = SystemClock.elapsedRealtime();
        if (interstitial == null || now - lastShown < 180000) return;
        InterstitialAd ad = interstitial;
        interstitial = null;
        lastShown = now;
        guideVisits = 0;
        ad.setFullScreenContentCallback(new FullScreenContentCallback() {
            public void onAdDismissedFullScreenContent() { preload(); }
            public void onAdFailedToShowFullScreenContent(AdError error) { preload(); }
        });
        ad.show(activity);
    }

    void privacyOptions() {
        if (alive()) UserMessagingPlatform.showPrivacyOptionsForm(activity, error -> {});
    }
}
