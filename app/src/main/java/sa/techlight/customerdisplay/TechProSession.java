package sa.techlight.customerdisplay;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Keeps the TechPro access token encrypted with a device-only Android key. */
public final class TechProSession {
    private static final String STORE = "techpro_session";
    private static final String KEY_ALIAS = "techlight_customer_display_session_v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SharedPreferences preferences;

    public TechProSession(Context context) {
        preferences = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    public synchronized void save(String token, String posCode, String userName, String accountName) throws Exception {
        if (token == null || token.trim().isEmpty()) throw new IllegalArgumentException("Missing token");
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(token.trim().getBytes(StandardCharsets.UTF_8));
        preferences.edit()
                .putString("token", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .putString("pos_code", posCode == null ? "" : posCode.trim())
                .putString("username", userName == null ? "" : userName.trim())
                .putString("account_name", accountName == null ? "" : accountName.trim())
                .putLong("login_at", System.currentTimeMillis())
                .apply();
    }

    public synchronized String token() {
        String encoded = preferences.getString("token", "");
        String encodedIv = preferences.getString("iv", "");
        if (encoded.isEmpty() || encodedIv.isEmpty()) return null;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    getOrCreateKey(),
                    new GCMParameterSpec(128, Base64.decode(encodedIv, Base64.NO_WRAP))
            );
            byte[] clear = cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP));
            String token = new String(clear, StandardCharsets.UTF_8).trim();
            return token.isEmpty() ? null : token;
        } catch (Exception error) {
            clear();
            return null;
        }
    }

    public boolean isSignedIn() {
        return token() != null;
    }

    public String posCode() {
        return preferences.getString("pos_code", "");
    }

    public String userName() {
        return preferences.getString("username", "");
    }

    public String accountName() {
        return preferences.getString("account_name", "");
    }

    public long loginAt() {
        return preferences.getLong("login_at", 0);
    }

    public void clear() {
        preferences.edit().clear().apply();
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
