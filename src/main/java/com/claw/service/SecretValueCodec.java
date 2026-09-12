package com.claw.service;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class SecretValueCodec {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();
    public SecretValueCodec(Environment environment) {
        String configured = environment.getProperty("app.security.encryption-key", "");
        if (configured.isBlank()) {
            throw new IllegalStateException("APP_CONFIG_ENCRYPTION_KEY must be configured before starting the application");
        }
        try { key = new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(configured.getBytes(StandardCharsets.UTF_8)), "AES"); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public String encrypt(String value) {
        try { byte[] iv = new byte[12]; random.nextBytes(iv); Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,iv)); byte[] data=c.doFinal(value.getBytes(StandardCharsets.UTF_8)); byte[] out=new byte[iv.length+data.length]; System.arraycopy(iv,0,out,0,iv.length); System.arraycopy(data,0,out,iv.length,data.length); return Base64.getEncoder().encodeToString(out); }
        catch(Exception e){throw new IllegalStateException("secret encryption failed",e);}
    }
    public String decrypt(String value) {
        try { byte[] all=Base64.getDecoder().decode(value); byte[] iv=java.util.Arrays.copyOfRange(all,0,12); byte[] data=java.util.Arrays.copyOfRange(all,12,all.length); Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,iv)); return new String(c.doFinal(data),StandardCharsets.UTF_8); }
        catch(Exception e){throw new IllegalStateException("secret decryption failed",e);}
    }
}
