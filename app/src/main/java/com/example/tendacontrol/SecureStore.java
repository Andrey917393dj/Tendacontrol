package com.example.tendacontrol;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import android.util.Base64;

public final class SecureStore {
    private static final String KS = "AndroidKeyStore";
    private static final String ALIAS = "tenda_phone_control_key";
    private static final String PREF = "secure_store";
    private static final String PASSWORD = "router_password";

    private SecureStore() {}

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KS);
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS);
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return kg.generateKey();
    }

    public static void savePassword(Context context, String password) {
        try {
            SecretKey key = getOrCreateKey();
            byte[] iv = new byte[12];
            new java.security.SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(password.getBytes(StandardCharsets.UTF_8));
            String value = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP);
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(PASSWORD, value).apply();
        } catch (Exception ignored) {}
    }

    public static String loadPassword(Context context) {
        String value = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(PASSWORD, null);
        if (value == null) return null;
        try {
            String[] p = value.split(":", 2);
            byte[] iv = Base64.decode(p[0], Base64.NO_WRAP);
            byte[] ct = Base64.decode(p[1], Base64.NO_WRAP);
            SecretKey key = getOrCreateKey();
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static void clear(Context context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply();
    }
}
