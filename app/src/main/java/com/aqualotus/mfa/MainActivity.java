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
import com.google.zxing.integration.android.IntentIntegrator;
import org.json.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.security.KeyStore;
import java.util.*;

public class MainActivity extends Activity {
    private final Handler clock = new Handler(Looper.getMainLooper());
    private JSONArray entries = new JSONArray();
    private final List<TextView> codes = new ArrayList<>();
    private LinearLayout list;
    private boolean vaultReady;
    private final Runnable tick = new Runnable() {
        public void run() { refresh(); clock.postDelayed(this, 250); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        try { load(); vaultReady = true; }
        catch (Exception e) { toast("Could not open your vault"); }
        render();
    }

    @Override protected void onResume() { super.onResume(); clock.post(tick); }
    @Override protected void onPause() { clock.removeCallbacks(tick); super.onPause(); }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView label(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size); view.setTextColor(0xff233348);
        return view;
    }
    private void button(String text, Runnable action) {
        Button view = new Button(this);
        view.setText(text); view.setAllCaps(false); view.setOnClickListener(v -> action.run());
        list.addView(view);
    }
    private void render() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xfff0f6f4);
        list = new LinearLayout(this);
        list.setOrientation(1);
        list.setPadding(dp(24), dp(64), dp(24), dp(40));
        scroll.addView(list);
        setContentView(scroll);
        list.addView(label("Authenticator", 32));
        list.addView(label("Your verification codes", 16));
        codes.clear();
        if (!vaultReady) {
            list.addView(label("Vault unavailable. Your saved data is still on this device.", 16));
            return;
        }
        button("Scan QR code", () -> {
            if (canAdd()) new IntentIntegrator(this).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .setPrompt("Scan a 2FA setup QR code").setBeepEnabled(false).initiateScan();
        });
        button("Enter setup key", this::manual);
        button("Switching authenticators?", () -> new AlertDialog.Builder(this).setTitle("Switch accounts")
            .setMessage("Add an account using its setup QR or key. Sign in with a code from this app. After a successful sign-in, hold the account card and mark it verified. Keep your old authenticator until all accounts work.")
            .setPositiveButton("Done", null).show());
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
            TextView code = label("", 27);
            code.setTypeface(Typeface.MONOSPACE);
            card.addView(code); codes.add(code);
            card.setOnClickListener(v -> copy(item));
            card.setOnLongClickListener(v -> { manage(index); return true; });
        }
        refresh();
    }

    private boolean canAdd() {
        if (entries.length() < 3) return true;
        toast("The free plan supports up to three accounts.");
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

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        com.google.zxing.integration.android.IntentResult scan = IntentIntegrator.parseActivityResult(request, result, data);
        if (scan == null || scan.getContents() == null || !vaultReady) return;
        try {
            Uri uri = Uri.parse(scan.getContents());
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
                codes.get(i).setText(current(item, now) + "   ·   " + (period - now % period) + "s");
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
