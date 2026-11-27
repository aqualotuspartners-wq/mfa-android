package com.aqualotus.mfa;

import android.app.*;
import android.content.*;
import android.os.*;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.security.keystore.*;
import android.util.Base64;
import android.view.*;
import android.widget.*;
import com.google.android.gms.ads.AdView;
import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;
import org.json.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.security.KeyStore;
import java.util.*;

public class MainActivity extends Activity {
    private final Handler clock = new Handler(Looper.getMainLooper());
    private JSONArray entries = new JSONArray();
    private final List<TextView> codes = new ArrayList<>();
    private final List<ProgressBar> timers = new ArrayList<>();
    private LinearLayout list;
    private boolean vaultReady;
    private Pro billing;
    private boolean proActive;
    private boolean entitlementKnown;
    private Ads ads;
    private AlertDialog infoDialog;
    private DecoratedBarcodeView scanner;
    private static final int CAMERA_REQUEST = 21;
    private static final int IMAGE_REQUEST = 22;
    private final Runnable tick = new Runnable() {
        public void run() { refresh(); clock.postDelayed(this, 250); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        try { load(); vaultReady = true; }
        catch (Exception e) { toast("Could not open your vault"); }
        render();
        ads = new Ads(this, () -> entitlementKnown && !proActive);
        ads.start();
        billing = new Pro(this, active -> {
            entitlementKnown = true;
            if (!active) ads.loadIfAllowed();
            if (proActive == active) return;
            proActive = active;
            if (active && infoDialog != null) infoDialog.dismiss();
            if (scanner == null) render();
        });
    }

    @Override protected void onDestroy() {
        if (infoDialog != null) infoDialog.dismiss();
        if (billing != null) billing.close();
        super.onDestroy();
    }

    @Override protected void onResume() { super.onResume(); if (billing != null) billing.refresh(); if (scanner != null) scanner.resume(); clock.post(tick); }
    @Override protected void onPause() { clock.removeCallbacks(tick); if (scanner != null) scanner.pause(); super.onPause(); }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView label(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size); view.setTextColor(0xff233348);
        return view;
    }
    private Button actionButton(String text, boolean primary, Runnable action) {
        Button view = new Button(this);
        view.setText(text); view.setAllCaps(false); view.setOnClickListener(v -> action.run());
        GradientDrawable surface = new GradientDrawable();
        surface.setCornerRadius(dp(18));
        surface.setColor(primary ? 0xff0c5145 : 0xffe2eee8);
        view.setBackground(new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x22459879), surface, null));
        view.setTextColor(primary ? Color.WHITE : 0xff174b42);
        return view;
    }

    private void button(String text, Runnable action) {
        LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(-1, dp(52));
        row.topMargin = dp(10);
        list.addView(actionButton(text, text.equals("+ Add account"), action), row);
    }

    private void applyInsets(View view) {
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
    }

    private void render() {
        if (scanner != null) {
            scanner.pause();
            scanner = null;
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xfff3f8f5);
        list = new LinearLayout(this);
        list.setOrientation(1);
        list.setPadding(dp(24), dp(28), dp(24), dp(24));
        applyInsets(scroll);
        scroll.addView(list);
        setContentView(scroll);
        TextView eyebrow = label("MFA  •  ON THIS DEVICE", 12);
        eyebrow.setLetterSpacing(.13f);
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(22), dp(22), dp(22), dp(22));
        header.setBackground(panel(0xff123e37, 28));
        list.addView(header);
        eyebrow.setTextColor(0xffa8e6c8);
        header.addView(eyebrow);
        TextView title = label("Your codes", 38);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextColor(Color.WHITE);
        title.setPadding(0, dp(8), 0, dp(6));
        header.addView(title);
        TextView count = label(proActive ? entries.length() + " accounts · Pro" : entries.length() + " of 3 accounts", 15);
        count.setTextColor(0xffc4e5d5);
        header.addView(count);
        codes.clear();
        timers.clear();
        if (!vaultReady) {
            list.addView(label("Vault unavailable. Your saved data is still on this device.", 16));
            return;
        }
        if (entries.length() == 0) {
            TextView empty = label("Add your first account\nUse the setup QR code from the service you want to protect.", 17);
            empty.setPadding(0, dp(24), 0, dp(16));
            list.addView(empty);
        }
        for (int i = 0; i < entries.length(); i++) {
            final int index = i;
            JSONObject item = entries.optJSONObject(i);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(1);
            card.setPadding(dp(18), dp(14), dp(18), dp(14));
            GradientDrawable background = new GradientDrawable();
            background.setColor(i % 2 == 0 ? 0xffdceee8 : 0xffe7e7fa);
            background.setCornerRadius(dp(20)); card.setBackground(background);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.topMargin = dp(12);
            list.addView(card, params);
            card.addView(label(item.optString("name") + (item.optBoolean("verified") ? "  ✓" : ""), 17));
            TextView code = label("", 32);
            code.setTypeface(Typeface.MONOSPACE);
            card.addView(code);
            codes.add(code);
            ProgressBar timer = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            timer.setProgressTintList(android.content.res.ColorStateList.valueOf(0xff25765e));
            timer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            card.addView(timer, new LinearLayout.LayoutParams(-1, dp(8)));
            timers.add(timer);
            TextView copyHint = label("Tap to copy", 13);
            copyHint.setTextColor(0xff295c50);
            copyHint.setPadding(0, dp(5), 0, 0);
            card.addView(copyHint);
            card.setOnClickListener(v -> copy(item));
            card.setOnLongClickListener(v -> { manage(index); return true; });
        }
        button("+ Add account", this::showAddOptions);
        if (!proActive) {
            button("Explore MFA Pro", this::upgrade);
            button("Restore purchases", () -> { if (billing != null) billing.refresh(); });
        }
        button("Switching authenticators?", () -> showInfo(true));
        button("Help & privacy", () -> showInfo(false));
        refresh();
    }

    private void showInfo(boolean transfer) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        body.setPadding(dp(18), dp(8), dp(18), dp(16));
        String message = transfer
            ? "1. Add an account using a setup QR or key.\n\n2. Sign in with a code from this app.\n\n3. Hold the account card and mark it verified. Keep your old authenticator until every account works."
            : "Tap a card to copy a code. Hold it to manage the account.";
        body.addView(label(message, 16));
        final LinearLayout offer;
        if (transfer) {
            offer = new LinearLayout(this);
            offer.setOrientation(LinearLayout.VERTICAL);
            body.addView(offer, new LinearLayout.LayoutParams(-1, -2));
        } else {
            offer = null;
        }
        final AdView[] banner = new AdView[]{ads.banner(body, transfer)};
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
            .setTitle(transfer ? "Switch accounts" : "Help & privacy")
            .setView(body).setPositiveButton("Done", null);
        if (!transfer) {
            if (ads.privacyOptionsRequired()) {
                dialog.setNeutralButton("Privacy options", (current, choice) -> ads.privacyOptions());
            }
            dialog.setNegativeButton("Privacy policy", (current, choice) ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                    "https://mfa-authenticator-privacy.ghjjjje.chatgpt.site"))));
        }
        infoDialog = dialog.create();
        infoDialog.setOnDismissListener(ignored -> {
            ads.setRewardReadyListener(null);
            if (banner[0] != null) {
                banner[0].destroy();
                banner[0] = null;
            }
            infoDialog = null;
        });
        infoDialog.show();
        if (transfer) {
            Runnable present = () -> presentRewardOffer(offer, body, banner);
            ads.setRewardReadyListener(present);
            present.run();
            ads.preload();
        }
    }

    private void presentRewardOffer(LinearLayout offer, LinearLayout body, AdView[] banner) {
        if (isFinishing() || isDestroyed() || infoDialog == null || !infoDialog.isShowing()) return;
        if (offer == null || !ads.rewardReady() || offer.findViewWithTag("reward-offer") != null) return;
        TextView note = label("Optional: watch a short ad to support this free app. Banners stay hidden for the rest of this session.", 15);
        note.setTag("reward-offer");
        LinearLayout.LayoutParams noteRow = new LinearLayout.LayoutParams(-1, -2);
        noteRow.topMargin = dp(16);
        offer.addView(note, noteRow);
        Button watch = actionButton("Watch a short ad", false, () -> {
            if (!ads.showRewarded(() -> grantBannerBreak(offer, body, banner))) return;
            if (offer.findViewWithTag("reward-offer") != null) offer.removeAllViews();
        });
        watch.setTag("reward-offer");
        watch.setContentDescription("Watch a short ad. Optional support for this free app. Banners stay hidden for the rest of this session.");
        LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(-1, dp(52));
        row.topMargin = dp(10);
        offer.addView(watch, row);
    }

    private void grantBannerBreak(LinearLayout offer, LinearLayout body, AdView[] banner) {
        ads.setRewardReadyListener(null);
        if (infoDialog == null || !infoDialog.isShowing() || offer.findViewWithTag("reward-thanks") != null) return;
        offer.removeAllViews();
        TextView thanks = label("Thank you. Banners are hidden for this session.", 15);
        thanks.setTag("reward-thanks");
        LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(-1, -2);
        row.topMargin = dp(16);
        offer.addView(thanks, row);
        if (banner[0] == null) return;
        body.removeView(banner[0]);
        banner[0].destroy();
        banner[0] = null;
    }

    private GradientDrawable panel(int color, int radius) {
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(color);
        surface.setCornerRadius(dp(radius));
        return surface;
    }

    private void showAddOptions() {
        if (!canAdd()) return;
        String[] options = {"Scan with camera", "Choose QR image", "Enter setup key"};
        new AlertDialog.Builder(this).setTitle("Add an account")
            .setItems(options, (dialog, selected) -> {
                switch (selected) {
                    case 0: requestScanner(); break;
                    case 1: chooseQrImage(); break;
                    case 2: manual(); break;
                }
            }).setNegativeButton("Cancel", null).show();
    }

    private void upgrade() {
        if (billing != null) billing.buy(this::toast);
    }

    private boolean canAdd() {
        if (proActive || entries.length() < 3) return true;
        new AlertDialog.Builder(this).setTitle("Three accounts added")
            .setMessage("MFA Pro lets you add unlimited accounts. Your existing codes remain available.")
            .setNegativeButton("Cancel", null).setPositiveButton("View Pro", (dialog, which) -> upgrade()).show();
        return false;
    }

    private void manual() {
        if (!canAdd()) return;
        LinearLayout fields = new LinearLayout(this); fields.setOrientation(1);
        EditText name = new EditText(this); name.setHint("Account name"); fields.addView(name);
        EditText key = new EditText(this); key.setHint("Setup key");
        key.setInputType(129); fields.addView(key);
        new AlertDialog.Builder(this).setTitle("Add account").setView(fields)
            .setNegativeButton("Cancel", null).setPositiveButton("Add", (d, w) ->
                add(name.getText().toString(), key.getText().toString(), "SHA1", 30, 6)).show();
    }

    private void requestScanner() {
        if (!canAdd()) return;
        if (checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            showScanner();
        } else {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, CAMERA_REQUEST);
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != CAMERA_REQUEST) return;
        if (results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) showScanner();
        else toast("Camera access is required to scan. You can enter a setup key instead.");
    }

    private void showScanner() {
        LinearLayout cameraPage = new LinearLayout(this);
        cameraPage.setOrientation(LinearLayout.VERTICAL);
        cameraPage.setBackgroundColor(0xfff3f8f5);
        applyInsets(cameraPage);
        list = cameraPage;
        button("‹  Back to codes", this::render);
        TextView instruction = label("Point your camera at a 2FA setup QR code", 17);
        instruction.setPadding(dp(20), dp(14), dp(20), dp(14));
        cameraPage.addView(instruction);
        scanner = new DecoratedBarcodeView(this);
        scanner.getBarcodeView().setDecoderFactory(
            new DefaultDecoderFactory(Collections.singletonList(BarcodeFormat.QR_CODE)));
        cameraPage.addView(scanner, new LinearLayout.LayoutParams(-1, 0, 1));
        button("Enter setup key instead", () -> { render(); manual(); });
        setContentView(cameraPage);
        scanner.getBarcodeView().addStateListener(new com.journeyapps.barcodescanner.CameraPreview.StateListener() {
            public void previewSized() {}
            public void previewStarted() {}
            public void previewStopped() {}
            public void cameraClosed() {}
            public void cameraError(Exception error) {
                if (scanner == null) return;
                render();
                toast("Camera unavailable. Enter the setup key instead.");
            }
        });
        scanner.decodeSingle(result -> {
            if (scanner == null) return;
            render();
            addFromQr(result.getText());
        });
        scanner.resume();
    }

    @Override public void onBackPressed() {
        if (scanner != null) render();
        else super.onBackPressed();
    }

    private void chooseQrImage() {
        if (!canAdd()) return;
        Intent picker = new Intent(Intent.ACTION_GET_CONTENT);
        picker.setType("image/*");
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(picker, IMAGE_REQUEST);
        } catch (ActivityNotFoundException exception) {
            toast("No image picker is available.");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != IMAGE_REQUEST || result != RESULT_OK || data == null
            || data.getData() == null || !vaultReady) return;
        Uri image = data.getData();
        new Thread(() -> {
            try {
                String value = readQrImage(image);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) addFromQr(value);
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        toast("No readable QR code. Try a clear screenshot or enter the setup key.");
                    }
                });
            }
        }, "mfa-image-decoder").start();
    }

    private String readQrImage(Uri image) throws Exception {
        android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (java.io.InputStream stream = getContentResolver().openInputStream(image)) {
            android.graphics.BitmapFactory.decodeStream(stream, null, options);
        }
        if (options.outWidth < 1 || options.outHeight < 1) throw new IllegalArgumentException();
        options.inSampleSize = 1;
        int largestSide = Math.max(options.outWidth, options.outHeight);
        while (largestSide / options.inSampleSize > 2048) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        try (java.io.InputStream stream = getContentResolver().openInputStream(image)) {
            android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeStream(stream, null, options);
            if (bitmap == null) throw new IllegalArgumentException();
            try {
                int width = bitmap.getWidth();
                int height = bitmap.getHeight();
                int[] pixels = new int[width * height];
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
                com.google.zxing.LuminanceSource source =
                    new com.google.zxing.RGBLuminanceSource(width, height, pixels);
                com.google.zxing.BinaryBitmap binary = new com.google.zxing.BinaryBitmap(
                    new com.google.zxing.common.HybridBinarizer(source));
                return new com.google.zxing.qrcode.QRCodeReader().decode(binary,
                    Collections.singletonMap(com.google.zxing.DecodeHintType.TRY_HARDER, Boolean.TRUE)).getText();
            } finally {
                bitmap.recycle();
            }
        }
    }

    private void addFromQr(String value) {
        try {
            Uri uri = Uri.parse(value);
            if (!"otpauth".equals(uri.getScheme()) || !"totp".equals(uri.getHost())) throw new IllegalArgumentException();
            String label = uri.getLastPathSegment();
            add(label == null ? "" : label, uri.getQueryParameter("secret"),
                parameter(uri, "algorithm", "SHA1").toUpperCase(Locale.ROOT),
                Integer.parseInt(parameter(uri, "period", "30")),
                Integer.parseInt(parameter(uri, "digits", "6")));
        } catch (Exception e) { toast("This is not a supported 2FA setup QR code"); }
    }
    private String parameter(Uri uri, String name, String fallback) {
        String value = uri.getQueryParameter(name);
        return value == null ? fallback : value;
    }
    private void add(String name, String secret, String algorithm, int period, int digits) {
        if (!canAdd()) return;
        try {
            if (secret == null || secret.length() > 1024 || name.trim().isEmpty() || name.length() > 250 ||
                !Arrays.asList("SHA1", "SHA256", "SHA512").contains(algorithm) ||
                period < 1 || period > 300 || (digits != 6 && digits != 8)) throw new IllegalArgumentException();
            Totp.decode(secret);
            JSONArray updated = new JSONArray(entries.toString());
            updated.put(new JSONObject().put("name", name.trim()).put("secret", secret)
                .put("algorithm", algorithm).put("period", period).put("digits", digits));
            save(updated);
        } catch (Exception e) { toast("Could not add account. Check the name and setup key."); }
    }
    private void manage(int index) {
        new AlertDialog.Builder(this).setItems(new String[]{"Mark verified", "Delete account"}, (d, which) -> {
            if (which == 0) update(index, false);
            else new AlertDialog.Builder(this).setTitle("Delete this account?")
                .setMessage("Make sure you can still sign in to the service before deleting its setup key.")
                .setNegativeButton("Cancel", null).setPositiveButton("Delete", (a, b) -> update(index, true)).show();
        }).show();
    }
    private void update(int index, boolean remove) {
        try {
            JSONArray updated = new JSONArray(entries.toString());
            if (remove) updated.remove(index);
            else updated.getJSONObject(index).put("verified", true);
            save(updated);
        } catch (Exception e) { toast("Could not update account"); }
    }
    private String current(JSONObject item, long now) throws Exception {
        return Totp.code(Totp.decode(item.getString("secret")), now,
            item.getInt("period"), item.getInt("digits"), item.getString("algorithm"));
    }
    private void refresh() {
        long now = System.currentTimeMillis() / 1000;
        for (int i = 0; i < codes.size(); i++) {
            try {
                JSONObject item = entries.getJSONObject(i);
                int period = item.getInt("period");
                int remaining = period - (int) (now % period);
                String code = current(item, now);
                int midpoint = code.length() / 2;
                String formatted = code.substring(0, midpoint) + " " + code.substring(midpoint);
                codes.get(i).setText(formatted + "  ·  " + remaining + "s");
                timers.get(i).setMax(period);
                timers.get(i).setProgress(remaining);
            } catch (Exception e) { codes.get(i).setText("Code unavailable"); }
        }
    }
    private void copy(JSONObject item) {
        try {
            ClipData clip = ClipData.newPlainText("Verification code", current(item, System.currentTimeMillis() / 1000));
            PersistableBundle extras = new PersistableBundle(); extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
            clip.getDescription().setExtras(extras);
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(clip);
            list.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            toast("Copied");
        } catch (Exception e) { toast("Could not copy code"); }
    }

    private javax.crypto.SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        String alias = "mfa-vault";
        if (store.containsAlias(alias)) return (javax.crypto.SecretKey) store.getKey(alias, null);
        if (!create) throw new IllegalStateException("Vault key unavailable");
        KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    private void load() throws Exception {
        String saved = getPreferences(MODE_PRIVATE).getString("vault", null);
        if (saved == null) return;
        byte[] payload = Base64.decode(saved, Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, payload, 0, 12));
        entries = new JSONArray(new String(cipher.doFinal(payload, 12, payload.length - 12), java.nio.charset.StandardCharsets.UTF_8));
    }
    private void save(JSONArray updated) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(true));
        byte[] body = cipher.doFinal(updated.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        if (iv.length != 12) throw new IllegalStateException();
        byte[] payload = java.nio.ByteBuffer.allocate(iv.length + body.length).put(iv).put(body).array();
        if (!getPreferences(MODE_PRIVATE).edit().putString("vault", Base64.encodeToString(payload, Base64.NO_WRAP)).commit())
            throw new IllegalStateException();
        entries = updated;
        render();
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
}
