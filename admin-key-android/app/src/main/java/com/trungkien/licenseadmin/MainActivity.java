package com.trungkien.licenseadmin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import android.util.Base64;

public class MainActivity extends Activity {
    private static final int REQ_IMPORT_PRIVATE_KEY = 1201;
    private static final String PREFS = "admin_key_secure";
    private static final String PREF_IV = "private_key_iv";
    private static final String PREF_CIPHER = "private_key_cipher";
    private static final String AES_ALIAS = "TRUNGKIEN_ADMIN_KEY_AES_V1";
    private static final String LICENSE_PREFIX = "DG12";
    private static final String PUBLIC_KEY_DER_B64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEHbkF3spSsePMGGCV1ccOxIE7lhYe5LfUK0wnTarf48icE9SR9L4KsKRMmSw3/KQ5Pgt0JhQBPCYyKAE0oGGuXQ==";
    private static final long DAY_MS = 86_400_000L;

    private final SecureRandom random = new SecureRandom();
    private TextView keyStatus;
    private EditText deviceInput;
    private Spinner validitySpinner;
    private EditText customDateInput;
    private EditText outputKey;
    private TextView expiryInfo;
    private ScrollView rootScroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(5, 8, 9));
        getWindow().setNavigationBarColor(Color.rgb(5, 8, 9));
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        setContentView(buildUi());
        refreshPrivateKeyStatus();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        rootScroll = scroll;
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(5, 8, 9));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView title = text("TRUNGKIEN ADMIN KEY", 27, Color.WHITE, true);
        root.addView(title);
        TextView sub = text("CẤP KEY V12 / V13 · OFFLINE SIGNING", 13, Color.rgb(34, 211, 197), true);
        sub.setPadding(0, dp(4), 0, dp(20));
        root.addView(sub);

        LinearLayout secretCard = card();
        root.addView(secretCard);
        secretCard.addView(text("PRIVATE SIGNING KEY", 13, Color.LTGRAY, true));
        keyStatus = text("ĐANG KIỂM TRA…", 16, Color.WHITE, true);
        keyStatus.setPadding(0, dp(8), 0, dp(12));
        secretCard.addView(keyStatus);
        secretCard.addView(text("APK Admin không chứa private key. Hãy nhập license_private_key_pkcs8.pem một lần. Key được mã hóa bằng Android Keystore trên máy Admin này.", 13, Color.rgb(190, 195, 198), false));

        Button importButton = button("NHẬP PRIVATE KEY");
        importButton.setOnClickListener(v -> importPrivateKey());
        secretCard.addView(importButton, buttonParams());

        Button clearButton = button("XÓA PRIVATE KEY KHỎI MÁY");
        clearButton.setOnClickListener(v -> confirmClearPrivateKey());
        secretCard.addView(clearButton, buttonParams());

        LinearLayout generateCard = card();
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, -2);
        gp.topMargin = dp(16);
        root.addView(generateCard, gp);
        generateCard.addView(text("TẠO LICENSE", 17, Color.WHITE, true));

        deviceInput = edit("MÃ THIẾT BỊ · VD: 1E3B-3D84-3714-F0E3");
        deviceInput.setSingleLine(true);
        deviceInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        deviceInput.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new InputFilter.LengthFilter(19)});
        generateCard.addView(deviceInput, fieldParams());

        Button pasteDeviceButton = button("DÁN MÃ THIẾT BỊ");
        pasteDeviceButton.setOnClickListener(v -> pasteDeviceCode());
        LinearLayout.LayoutParams pasteDeviceParams = buttonParams();
        pasteDeviceParams.topMargin = dp(8);
        generateCard.addView(pasteDeviceButton, pasteDeviceParams);

        String[] validity = {"VĨNH VIỄN", "30 NGÀY", "90 NGÀY", "365 NGÀY", "ĐẾN NGÀY CỤ THỂ"};
        validitySpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, validity) {
            @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView v = (TextView) super.getView(position, convertView, parent);
                v.setTextColor(Color.WHITE); v.setTextSize(15); v.setPadding(dp(12), dp(14), dp(12), dp(14));
                return v;
            }
            @Override public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
                TextView v = (TextView) super.getDropDownView(position, convertView, parent);
                v.setTextColor(Color.BLACK); v.setTextSize(15); return v;
            }
        };
        validitySpinner.setAdapter(adapter);
        validitySpinner.setBackgroundColor(Color.rgb(35, 40, 43));
        LinearLayout.LayoutParams sp = fieldParams();
        generateCard.addView(validitySpinner, sp);

        customDateInput = edit("YYYY-MM-DD · VD: 2027-12-31");
        customDateInput.setSingleLine(true);
        customDateInput.setVisibility(View.GONE);
        generateCard.addView(customDateInput, fieldParams());
        validitySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                customDateInput.setVisibility(position == 4 ? View.VISIBLE : View.GONE);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        Button generateButton = button("TẠO KEY");
        generateButton.setTextSize(18);
        generateButton.setOnClickListener(v -> generateLicense());
        LinearLayout.LayoutParams gbp = buttonParams();
        gbp.topMargin = dp(18);
        generateCard.addView(generateButton, gbp);

        expiryInfo = text("", 13, Color.rgb(34, 211, 197), false);
        expiryInfo.setPadding(0, dp(12), 0, 0);
        generateCard.addView(expiryInfo);

        outputKey = edit("KEY SẼ HIỂN THỊ Ở ĐÂY");
        outputKey.setMinLines(5);
        outputKey.setGravity(Gravity.TOP | Gravity.START);
        outputKey.setTextIsSelectable(true);
        outputKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        outputKey.setShowSoftInputOnFocus(false);
        outputKey.setCursorVisible(false);
        generateCard.addView(outputKey, fieldParams());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button copy = button("SAO CHÉP");
        copy.setOnClickListener(v -> copyOutput());
        Button share = button("CHIA SẺ");
        share.setOnClickListener(v -> shareOutput());
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(52), 1f);
        half.setMargins(0, dp(10), dp(5), 0);
        actions.addView(copy, half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, dp(52), 1f);
        half2.setMargins(dp(5), dp(10), 0, 0);
        actions.addView(share, half2);
        generateCard.addView(actions);

        TextView warning = text("BẢO MẬT: Không gửi file private key và không chia sẻ bản sao dữ liệu ứng dụng Admin. Chỉ gửi chuỗi license đã tạo cho người dùng.", 12, Color.rgb(255, 193, 7), false);
        warning.setPadding(0, dp(20), 0, 0);
        root.addView(warning);

        return scroll;
    }

    private void importPrivateKey() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_IMPORT_PRIVATE_KEY);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT_PRIVATE_KEY || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            byte[] raw = readAll(getContentResolver().openInputStream(uri));
            String pem = new String(raw, StandardCharsets.UTF_8);
            byte[] der = parsePkcs8Pem(pem);
            PrivateKey privateKey = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
            if (!matchesAppPublicKey(privateKey)) throw new IllegalArgumentException("Private key không khớp public key trong TRUNGKIEN V12/V13.");
            storePrivateKey(der);
            refreshPrivateKeyStatus();
            toast("Đã nhập private key an toàn.");
        } catch (Exception e) {
            showError("Không nhập được private key", e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private byte[] parsePkcs8Pem(String pem) {
        if (!pem.contains("BEGIN PRIVATE KEY")) {
            throw new IllegalArgumentException("Hãy chọn file license_private_key_pkcs8.pem (PKCS#8). Không chọn file EC PRIVATE KEY.");
        }
        String b64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        return Base64.decode(b64, Base64.DEFAULT);
    }

    private boolean matchesAppPublicKey(PrivateKey privateKey) throws Exception {
        byte[] msg = "TRUNGKIEN-ADMIN-KEY-CHECK-V12".getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(privateKey);
        signer.update(msg);
        byte[] sig = signer.sign();

        byte[] pubDer = Base64.decode(PUBLIC_KEY_DER_B64, Base64.DEFAULT);
        java.security.PublicKey pub = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(pubDer));
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(pub);
        verifier.update(msg);
        return verifier.verify(sig);
    }

    private void generateLicense() {
        hideKeyboard();
        deviceInput.clearFocus();
        customDateInput.clearFocus();
        try {
            byte[] privateDer = loadPrivateKey();
            if (privateDer == null) throw new IllegalStateException("Chưa nhập private key Admin.");
            PrivateKey privateKey = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(privateDer));

            String device = deviceInput.getText().toString().trim().toUpperCase(Locale.US);
            if (!device.matches("[0-9A-F]{4}(?:-[0-9A-F]{4}){3}")) {
                throw new IllegalArgumentException("Mã thiết bị không đúng định dạng XXXX-XXXX-XXXX-XXXX.");
            }

            long today = System.currentTimeMillis() / DAY_MS;
            long expiry;
            String validityText;
            switch (validitySpinner.getSelectedItemPosition()) {
                case 1: expiry = today + 30; validityText = "30 ngày"; break;
                case 2: expiry = today + 90; validityText = "90 ngày"; break;
                case 3: expiry = today + 365; validityText = "365 ngày"; break;
                case 4:
                    expiry = parseEpochDay(customDateInput.getText().toString().trim());
                    if (expiry < today) throw new IllegalArgumentException("Ngày hết hạn phải từ hôm nay trở đi.");
                    validityText = customDateInput.getText().toString().trim();
                    break;
                default: expiry = 0; validityText = "Vĩnh viễn"; break;
            }

            String serial = randomHex(4);
            String payloadText = LICENSE_PREFIX + "|" + device + "|" + expiry + "|" + serial;
            byte[] payload = payloadText.getBytes(StandardCharsets.UTF_8);

            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(privateKey, random);
            signer.update(payload);
            byte[] signature = signer.sign();

            String license = b64u(payload) + "." + b64u(signature);
            outputKey.setText(license);
            expiryInfo.setText("ĐÃ TẠO · " + validityText + " · " + device + " · SERIAL " + serial);
            Arrays.fill(privateDer, (byte)0);
            hideKeyboard();
            if (rootScroll != null) {
                rootScroll.post(() -> rootScroll.smoothScrollTo(0, Math.max(0, outputKey.getBottom() - dp(140))));
            }
            toast("Đã tạo key hợp lệ.");
        } catch (Exception e) {
            showError("Không tạo được key", e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private void pasteDeviceCode() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null
                    || cm.getPrimaryClip().getItemCount() == 0) {
                toast("Clipboard đang trống.");
                return;
            }

            CharSequence clipText = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            String raw = clipText == null ? "" : clipText.toString().trim().toUpperCase(Locale.US);
            Matcher matcher = Pattern.compile("[0-9A-F]{4}(?:-[0-9A-F]{4}){3}").matcher(raw);

            if (!matcher.find()) {
                showError("Không thấy mã thiết bị",
                        "Clipboard không có mã dạng XXXX-XXXX-XXXX-XXXX.");
                return;
            }

            String code = matcher.group();
            deviceInput.setText(code);
            deviceInput.setSelection(code.length());
            deviceInput.clearFocus();
            hideKeyboard();
            toast("Đã dán mã thiết bị.");
        } catch (Exception e) {
            showError("Không dán được mã thiết bị",
                    e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private void hideKeyboard() {
        try {
            View focus = getCurrentFocus();
            if (focus == null) focus = deviceInput;
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && focus != null) {
                imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
            }
        } catch (Exception ignored) { }
    }

    private long parseEpochDay(String dateText) throws Exception {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setLenient(false);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        Date d = f.parse(dateText);
        if (d == null) throw new IllegalArgumentException("Ngày không hợp lệ.");
        return d.getTime() / DAY_MS;
    }

    private void storePrivateKey(byte[] der) throws Exception {
        SecretKey aes = getOrCreateAesKey();
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, aes);
        byte[] cipher = c.doFinal(der);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PREF_IV, Base64.encodeToString(c.getIV(), Base64.NO_WRAP))
                .putString(PREF_CIPHER, Base64.encodeToString(cipher, Base64.NO_WRAP))
                .apply();
    }

    private byte[] loadPrivateKey() throws Exception {
        String ivB64 = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_IV, null);
        String cipherB64 = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_CIPHER, null);
        if (ivB64 == null || cipherB64 == null) return null;
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        SecretKey aes = (SecretKey) ks.getKey(AES_ALIAS, null);
        if (aes == null) return null;
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, aes, new GCMParameterSpec(128, Base64.decode(ivB64, Base64.DEFAULT)));
        return c.doFinal(Base64.decode(cipherB64, Base64.DEFAULT));
    }

    private SecretKey getOrCreateAesKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(AES_ALIAS)) return (SecretKey) ks.getKey(AES_ALIAS, null);
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(AES_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return kg.generateKey();
    }

    private void clearPrivateKey() {
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().apply();
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(AES_ALIAS)) ks.deleteEntry(AES_ALIAS);
            refreshPrivateKeyStatus();
            outputKey.setText("");
            toast("Đã xóa private key khỏi máy Admin.");
        } catch (Exception e) {
            showError("Không xóa được key", e.toString());
        }
    }

    private void confirmClearPrivateKey() {
        new AlertDialog.Builder(this)
                .setTitle("Xóa private key?")
                .setMessage("Sau khi xóa, bạn phải nhập lại license_private_key_pkcs8.pem mới có thể tạo license.")
                .setNegativeButton("HỦY", null)
                .setPositiveButton("XÓA", (d, w) -> clearPrivateKey())
                .show();
    }

    private void refreshPrivateKeyStatus() {
        try {
            byte[] der = loadPrivateKey();
            if (der != null) {
                PrivateKey pk = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
                boolean ok = matchesAppPublicKey(pk);
                Arrays.fill(der, (byte)0);
                keyStatus.setText(ok ? "✓ PRIVATE KEY ĐÃ SẴN SÀNG" : "⚠ PRIVATE KEY KHÔNG KHỚP");
                keyStatus.setTextColor(ok ? Color.rgb(34,211,197) : Color.rgb(255,193,7));
            } else {
                keyStatus.setText("CHƯA NHẬP PRIVATE KEY");
                keyStatus.setTextColor(Color.rgb(255,193,7));
            }
        } catch (Exception e) {
            keyStatus.setText("CẦN NHẬP LẠI PRIVATE KEY");
            keyStatus.setTextColor(Color.rgb(255,193,7));
        }
    }

    private void copyOutput() {
        String key = outputKey.getText().toString().trim();
        if (key.isEmpty()) { toast("Chưa có key để sao chép."); return; }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("TRUNGKIEN license", key));
        toast("Đã sao chép key.");
    }

    private void shareOutput() {
        String key = outputKey.getText().toString().trim();
        if (key.isEmpty()) { toast("Chưa có key để chia sẻ."); return; }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, key);
        startActivity(Intent.createChooser(i, "Chia sẻ license key"));
    }

    private static String b64u(byte[] b) {
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private String randomHex(int bytes) {
        byte[] b = new byte[bytes]; random.nextBytes(b);
        StringBuilder s = new StringBuilder();
        for (byte v : b) s.append(String.format(Locale.US, "%02X", v & 0xff));
        return s.toString();
    }

    private byte[] readAll(InputStream input) throws Exception {
        if (input == null) throw new IllegalArgumentException("Không đọc được file.");
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(16), dp(16), dp(16));
        l.setBackgroundColor(Color.rgb(22, 27, 30));
        return l;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private EditText edit(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setHintTextColor(Color.rgb(125, 132, 136));
        e.setTextColor(Color.WHITE); e.setTextSize(15);
        e.setBackgroundColor(Color.rgb(35, 40, 43));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        return e;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label); b.setTextColor(Color.WHITE); b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackgroundColor(Color.rgb(25, 118, 110));
        return b;
    }

    private LinearLayout.LayoutParams fieldParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(58));
        p.topMargin = dp(12); return p;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(52));
        p.topMargin = dp(10); return p;
    }

    private int dp(int x) { return Math.round(x * getResources().getDisplayMetrics().density); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private void showError(String title, String msg) { new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("ĐÓNG", null).show(); }
}
