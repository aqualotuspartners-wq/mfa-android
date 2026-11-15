package com.aqualotus.mfa;

import android.app.Activity;
import com.android.billingclient.api.*;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

final class Pro {
    private static final String PRODUCT = "mfa_pro";
    private static final String PLAN = "annual";
    private final Activity activity;
    private final Consumer<Boolean> status;
    private final BillingClient client;

    Pro(Activity activity, Consumer<Boolean> status) {
        this.activity = activity;
        this.status = status;
        client = BillingClient.newBuilder(activity)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .setListener((result, purchases) -> {
                if (result.getResponseCode() == BillingClient.BillingResponseCode.OK && purchases != null) refresh();
            }).build();
        client.startConnection(new BillingClientStateListener() {
            public void onBillingSetupFinished(BillingResult result) {
                if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) refresh();
            }
            public void onBillingServiceDisconnected() {}
        });
    }

    void refresh() {
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS).build(), (result, purchases) -> {
            if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) return;
            boolean active = false;
            for (Purchase purchase : purchases) {
                if (!purchase.getProducts().contains(PRODUCT)
                    || purchase.getPurchaseState() != Purchase.PurchaseState.PURCHASED) continue;
                active = true;
                if (!purchase.isAcknowledged()) {
                    client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.getPurchaseToken()).build(), ignored -> {});
                }
            }
            boolean entitled = active;
            activity.runOnUiThread(() -> { if (!activity.isDestroyed()) status.accept(entitled); });
        });
    }

    void buy(Consumer<String> error) {
        QueryProductDetailsParams.Product product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PRODUCT).setProductType(BillingClient.ProductType.SUBS).build();
        client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder()
            .setProductList(Collections.singletonList(product)).build(), (result, details) ->
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                for (ProductDetails item : details.getProductDetailsList()) {
                    List<ProductDetails.SubscriptionOfferDetails> offers = item.getSubscriptionOfferDetails();
                    if (offers == null) continue;
                    for (ProductDetails.SubscriptionOfferDetails offer : offers) {
                        if (!PLAN.equals(offer.getBasePlanId()) || offer.getOfferId() != null) continue;
                        String price = offer.getPricingPhases().getPricingPhaseList().get(0).getFormattedPrice();
                        new android.app.AlertDialog.Builder(activity).setTitle("MFA Pro")
                            .setMessage(price + " per year. Renews annually until canceled in Google Play.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Continue", (dialog, button) -> {
                                BillingResult launched = client.launchBillingFlow(activity, BillingFlowParams.newBuilder()
                                    .setProductDetailsParamsList(Collections.singletonList(
                                        BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(item)
                                            .setOfferToken(offer.getOfferToken()).build())).build());
                                if (launched.getResponseCode() != BillingClient.BillingResponseCode.OK)
                                    error.accept("Could not open Google Play checkout.");
                            }).show();
                        return;
                    }
                }
                error.accept("MFA Pro is not available from Google Play yet.");
            }));
    }

    void close() { client.endConnection(); }
}
