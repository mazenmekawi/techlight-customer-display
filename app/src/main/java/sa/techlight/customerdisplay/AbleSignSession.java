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

/** Device-only encrypted AbleSign API configuration used by the embedded player. */
public final class AbleSignSession {
    private static final String STORE = "ablesign_embedded";
    private static final String KEY_ALIAS = "techlight_ablesign_api_v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SharedPreferences preferences;

    public AbleSignSession(Context context) {
        preferences = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    public synchronized void save(String apiKey, long screenId, String workspaceId) throws Exception {
        String cleanKey = apiKey == null ? "" : apiKey.trim();
        if (cleanKey.isEmpty() || screenId <= 0) {
            clear();
            return;
        }
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(cleanKey.getBytes(StandardCharsets.UTF_8));
        preferences.edit()
                .putString("key", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .putLong("screen_id", screenId)
                .putString("workspace_id", workspaceId == null ? "" : workspaceId.trim())
                .apply();
    }

    public synchronized String apiKey() {
        String encoded = preferences.getString("key", "");
        String encodedIv = preferences.getString("iv", "");
        if (encoded.isEmpty() || encodedIv.isEmpty()) return "";
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
                    new GCMParameterSpec(128, Base64.decode(encodedIv, Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP)),
                    StandardCharsets.UTF_8).trim();
        } catch (Exception error) {
            clear();
            return "";
        }
    }

    public long screenId() {
        return preferences.getLong("screen_id", 0);
    }

    public String workspaceId() {
        return preferences.getString("workspace_id", "");
    }

    public boolean isConfigured() {
        return screenId() > 0 && !apiKey().isEmpty();
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
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
