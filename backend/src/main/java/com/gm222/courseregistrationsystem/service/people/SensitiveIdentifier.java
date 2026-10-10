package com.gm222.courseregistrationsystem.service.people;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SensitiveIdentifier {
  private final byte[] key;
  private final SecureRandom random = new SecureRandom();

  public SensitiveIdentifier(@Value("${app.ssn-key:}") String secret) {
    try {
      key =
          secret.isBlank()
              ? null
              : MessageDigest.getInstance("SHA-256")
                  .digest(secret.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void requireKey() {
    if (key == null)
      throw new com.gm222.courseregistrationsystem.exception.BusinessException(
          503, "SENSITIVE_KEY_UNAVAILABLE", "敏感标识密钥未配置，请联系管理员");
  }

  public byte[] fingerprint(String raw) {
    if (raw == null || raw.isBlank()) return null;
    requireKey();
    try {
      var mac = javax.crypto.Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(("SSN:" + normalize(raw)).getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException("敏感标识指纹计算失败", e);
    }
  }

  public byte[] encrypt(String raw) {
    if (raw == null || raw.isBlank()) return null;
    requireKey();
    try {
      byte[] iv = new byte[12];
      random.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
      byte[] body = cipher.doFinal(normalize(raw).getBytes(StandardCharsets.UTF_8));
      byte[] result = new byte[iv.length + body.length];
      System.arraycopy(iv, 0, result, 0, iv.length);
      System.arraycopy(body, 0, result, iv.length, body.length);
      return result;
    } catch (Exception e) {
      throw new IllegalStateException("敏感标识加密失败", e);
    }
  }

  public String decrypt(byte[] encrypted) {
    if (encrypted == null || encrypted.length < 13) return null;
    requireKey();
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(128, Arrays.copyOf(encrypted, 12)));
      return new String(
          cipher.doFinal(Arrays.copyOfRange(encrypted, 12, encrypted.length)),
          StandardCharsets.UTF_8);
    } catch (Exception e) {
      return null;
    }
  }

  public String mask(byte[] encrypted) {
    String value = decrypt(encrypted);
    return value == null || value.length() < 4
        ? null
        : "***-**-" + value.substring(value.length() - 4);
  }

  private String normalize(String value) {
    return value == null ? null : value.replace("-", "").trim();
  }

}
