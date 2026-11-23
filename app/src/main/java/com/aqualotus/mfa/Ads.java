package com.aqualotus.mfa;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.widget.LinearLayout;
import com.google.android.gms.ads.*;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;
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
    /** Earned by opting in on the guide dialog. Lasts until the process ends. */
    private static boolean bannersHidden;
    private RewardedAd rewarded;
    private Runnable rewardReadyListener;

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
        if (bannersHidden || !ready || !allowed()) return null;
        AdView view = new AdView(activity);
        view.setAdSize(AdSize.BANNER);
        view.setAdUnitId(debug ? "ca-app-pub-3940256099942544/6300978111" : transfer
            ? "ca-app-pub-3956817331315524/4759993650" : "ca-app-pub-3956817331315524/4979485509");
        container.addView(view, new LinearLayout.LayoutParams(-2, -2));
        view.loadAd(new AdRequest.Builder().build());
        return view;
    }

    boolean rewardReady() { return !bannersHidden && rewarded != null && allowed(); }

    void setRewardReadyListener(Runnable listener) { rewardReadyListener = listener; }

    void preload() {
        if (bannersHidden || !ready || !allowed() || loading || rewarded != null) return;
        loading = true;
        RewardedAd.load(activity, debug ? "ca-app-pub-3940256099942544/5224354917"
            : "ca-app-pub-3956817331315524/8707103839",
            new AdRequest.Builder().build(), new RewardedAdLoadCallback() {
                public void onAdLoaded(RewardedAd ad) {
                    loading = false;
                    if (!allowed() || bannersHidden) return;
                    rewarded = ad;
                    Runnable listener = rewardReadyListener;
                    if (listener != null) listener.run();
                }
                public void onAdFailedToLoad(LoadAdError error) { loading = false; }
            });
    }

    // Opt-in only. Never call this from a timer, dismiss listener, or load callback.
    boolean showRewarded(Runnable onEarned) {
        if (!rewardReady()) return false;
        RewardedAd ad = rewarded;
        rewarded = null;
        ad.setFullScreenContentCallback(new FullScreenContentCallback() {
            public void onAdDismissedFullScreenContent() { preload(); }
            public void onAdFailedToShowFullScreenContent(AdError error) { preload(); }
        });
        ad.show(activity, reward -> {
            bannersHidden = true;
            if (onEarned != null) onEarned.run();
        });
        return true;
    }

    void privacyOptions() {
        if (alive()) UserMessagingPlatform.showPrivacyOptionsForm(activity, error -> {});
    }
}
