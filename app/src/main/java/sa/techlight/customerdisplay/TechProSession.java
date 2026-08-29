package sa.techlight.customerdisplay;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Keeps the TechPro token encrypted on-device.
 * Uses AndroidKeyStore when available and a device-bound AES fallback for low-cost Android TV firmware
 * where the hardware keystore is occasionally incomplete or unstable.
 */
public final class TechProSession {
    private static final String STORE = "techpro_session";
    private static final String KEY_ALIAS = "techlight_customer_display_session_v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String SOFTWARE_SALT = "TechLight.Kitchen.Session.Fallback.v1";

    private final Context context;
    private final SharedPreferences preferences;

    public TechProSession(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    public synchronized void save(String token, String posCode, String userName, String accountName) throws Exception {
        if (token == null || token.trim().isEmpty()) throw new IllegalArgumentException("Missing token");
        String clearToken = token.trim();
        Exception primaryError = null;
        try {
            saveEncrypted(clearToken, getOrCreateKey(), "keystore", posCode, userName, accountName);
            return;
        } catch (Exception error) {
            primaryError = error;
        }
        try {
            saveEncrypted(clearToken, softwareKey(), "software", posCode, userName, accountName);
        } catch (Exception fallbackError) {
            if (primaryError != null) fallbackError.addSuppressed(primaryError);
            throw fallbackError;
        }
    }

    private void saveEncrypted(
            String token,
            SecretKey key,
            String mode,
            String posCode,
            String userName,
            String accountName
    ) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        preferences.edit()
                .putString("token", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .putString("mode", mode)
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
        String mode = preferences.getString("mode", "keystore");
        try {
            SecretKey key = "software".equals(mode) ? softwareKey() : getOrCreateKey();
            return decrypt(encoded, encodedIv, key);
        } catch (Exception primary) {
            // Old installs may not have a mode flag or a buggy keystore may become unavailable later.
            if (!"software".equals(mode)) {
                try {
                    String token = decrypt(encoded, encodedIv, softwareKey());
                    if (token != null) return token;
                } catch (Exception ignored) { }
            }
            return null;
        }
    }

    private String decrypt(String encoded, String encodedIv, SecretKey key) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                new GCMParameterSpec(128, Base64.decode(encodedIv, Base64.NO_WRAP))
        );
        byte[] clear = cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP));
        String token = new String(clear, StandardCharsets.UTF_8).trim();
        return token.isEmpty() ? null : token;
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

    private SecretKey softwareKey() throws Exception {
        String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId == null) androidId = "unknown-device";
        String material = androidId + "|" + context.getPackageName() + "|" + SOFTWARE_SALT;
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }
}
