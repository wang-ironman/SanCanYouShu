package cn.caidan.app;

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

/** Stores provider settings locally, encrypting the API key with Android Keystore. */
public final class AiSettings {
    private static final String PREFS = "ai_settings";
    private static final String KEY_ALIAS = "caidan_ai_api_key_v1";
    private static final String ENCRYPTED_KEY = "api_key_encrypted";

    private AiSettings() {}

    public static final class Config {
        public String baseUrl = "";
        public String apiKey = "";
        public String model = "";
    }

    public static Config load(Context context) throws Exception {
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Config config = new Config();
        config.baseUrl = preferences.getString("base_url", "");
        config.model = preferences.getString("model", "");
        String encrypted = preferences.getString(ENCRYPTED_KEY, null);
        if (encrypted != null && !encrypted.isEmpty()) {
            byte[] payload = Base64.decode(encrypted, Base64.NO_WRAP);
            if (payload.length <= 12) {
                throw new IllegalStateException("本机保存的 AI 密钥无法读取，请重新设置 AI 服务。");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), new GCMParameterSpec(128, payload, 0, 12));
            config.apiKey = new String(cipher.doFinal(payload, 12, payload.length - 12), StandardCharsets.UTF_8);
        }
        return config;
    }

    public static void save(Context context, Config config) throws Exception {
        if (context == null || config == null) {
            throw new IllegalArgumentException("AI 设置不能为空。");
        }
        // A restored preferences file may outlive its Keystore key. Do not silently
        // replace that unreadable key; load must succeed before any write is made.
        load(context);
        String baseUrl = config.baseUrl == null ? "" : config.baseUrl.trim();
        String model = config.model == null ? "" : config.model.trim();
        String apiKey = config.apiKey == null ? "" : config.apiKey;
        if (baseUrl.length() > 2048 || model.length() > 256 || apiKey.length() > 4096) {
            throw new IllegalArgumentException("AI 设置内容过长。");
        }

        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit().putString("base_url", baseUrl).putString("model", model);
        if (apiKey.isEmpty()) {
            editor.remove(ENCRYPTED_KEY);
        } else {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] iv = cipher.getIV();
            byte[] ciphertext = cipher.doFinal(apiKey.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(ciphertext, 0, payload, iv.length, ciphertext.length);
            editor.putString(ENCRYPTED_KEY, Base64.encodeToString(payload, Base64.NO_WRAP));
        }
        if (!editor.commit()) {
            throw new IllegalStateException("无法保存 AI 设置，请检查设备存储空间。");
        }
    }

    private static SecretKey getExistingKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        java.security.Key key = keyStore.getKey(KEY_ALIAS, null);
        if (!(key instanceof SecretKey)) {
            throw new IllegalStateException("本机加密密钥不可用，请清除此应用数据后重新设置 AI 服务。");
        }
        return (SecretKey) key;
    }

    private static SecretKey getOrCreateKey() throws Exception {
        try {
            return getExistingKey();
        } catch (IllegalStateException missing) {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) {
                throw missing;
            }
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
}
