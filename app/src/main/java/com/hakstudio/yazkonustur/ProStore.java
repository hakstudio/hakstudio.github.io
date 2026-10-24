package com.hakstudio.yazkonustur;

import android.content.SharedPreferences;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;

import org.json.JSONObject;

import java.util.Collections;
import java.util.List;

/** Tek seferlik "Pro" satın alımı (Google Play Billing). Ürün kimliği Play Console'da aynen açılmalı. */
public class ProStore implements PurchasesUpdatedListener {
    static final String PRODUCT = "pro_lifetime";

    final MainActivity a;
    final SharedPreferences prefs;
    final BillingClient client;
    ProductDetails details;
    boolean connecting;

    ProStore(MainActivity a) {
        this.a = a;
        prefs = a.getSharedPreferences("yk", MainActivity.MODE_PRIVATE);
        client = BillingClient.newBuilder(a)
                .setListener(this)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .enableAutoServiceReconnection()
                .build();
        connect(null);
    }

    boolean isPro() {
        return prefs.getBoolean("pro", false) || prefs.getBoolean("debugPro", false);
    }

    String price() {
        return prefs.getString("price", "");
    }

    void connect(Runnable then) {
        if (client.isReady()) {
            if (then != null) then.run();
            return;
        }
        if (connecting) return;
        connecting = true;
        client.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult r) {
                connecting = false;
                if (r.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    loadDetails();
                    restore(false);
                    if (then != null) then.run();
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                connecting = false;
            }
        });
    }

    void loadDetails() {
        QueryProductDetailsParams p = QueryProductDetailsParams.newBuilder()
                .setProductList(Collections.singletonList(QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT).setProductType(BillingClient.ProductType.INAPP).build()))
                .build();
        client.queryProductDetailsAsync(p, (r, result) -> {
            List<ProductDetails> list = result.getProductDetailsList();
            if (r.getResponseCode() == BillingClient.BillingResponseCode.OK && list != null && !list.isEmpty()) {
                details = list.get(0);
                ProductDetails.OneTimePurchaseOfferDetails o = details.getOneTimePurchaseOfferDetails();
                if (o != null) prefs.edit().putString("price", o.getFormattedPrice()).apply();
                pushToPage();
            }
        });
    }

    void restore(boolean verbose) {
        if (!client.isReady()) {
            connect(() -> restore(verbose));
            return;
        }
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(), (r, purchases) -> {
            if (r.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                if (verbose) pushToPage("Google Play'e ulaşılamadı, sonra tekrar dene");
                return;
            }
            boolean owned = false;
            for (Purchase p : purchases) {
                if (p.getProducts().contains(PRODUCT) && p.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                    owned = true;
                    acknowledge(p);
                }
            }
            boolean was = prefs.getBoolean("pro", false);
            prefs.edit().putBoolean("pro", owned).apply();
            if (verbose) pushToPage(owned ? "Pro geri yüklendi" : "Bu hesapta Pro satın alımı bulunamadı");
            else if (was != owned) pushToPage();
        });
    }

    void refresh() {
        if (client.isReady()) restore(false);
        else connect(null);
    }

    void buy() {
        if (!client.isReady() || details == null) {
            pushToPage("Mağaza hazırlanıyor, birkaç saniye sonra tekrar dene");
            connect(this::loadDetails);
            return;
        }
        BillingFlowParams f = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(Collections.singletonList(
                        BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).build()))
                .build();
        client.launchBillingFlow(a, f);
    }

    @Override
    public void onPurchasesUpdated(BillingResult r, List<Purchase> purchases) {
        int code = r.getResponseCode();
        if (code == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (Purchase p : purchases) {
                if (!p.getProducts().contains(PRODUCT)) continue;
                if (p.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                    prefs.edit().putBoolean("pro", true).apply();
                    acknowledge(p);
                    pushToPage("Pro etkin. Teşekkürler!");
                } else if (p.getPurchaseState() == Purchase.PurchaseState.PENDING) {
                    pushToPage("Ödeme bekleniyor. Onaylanınca Pro kendiliğinden açılır.");
                }
            }
        } else if (code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
            prefs.edit().putBoolean("pro", true).apply();
            pushToPage("Pro zaten bu hesapta var, açıldı");
        } else if (code != BillingClient.BillingResponseCode.USER_CANCELED) {
            pushToPage("Satın alma tamamlanamadı (" + code + ")");
        }
    }

    void acknowledge(Purchase p) {
        if (p.isAcknowledged()) return;
        client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.getPurchaseToken()).build(), r -> { });
    }

    void debugToggle() {
        boolean v = !prefs.getBoolean("debugPro", false);
        prefs.edit().putBoolean("debugPro", v).apply();
        pushToPage(v ? "Deneme: Pro açık" : "Deneme: Pro kapalı");
    }

    void pushToPage() {
        pushToPage(null);
    }

    void pushToPage(String message) {
        try {
            JSONObject o = new JSONObject().put("pro", isPro()).put("price", price());
            if (message != null) o.put("message", message);
            a.js("window.YKApp && YKApp.pro(" + o + ")");
        } catch (Exception ignored) {
        }
    }

    void close() {
        client.endConnection();
    }
}
