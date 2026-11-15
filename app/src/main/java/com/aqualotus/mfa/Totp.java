package com.aqualotus.mfa;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class Totp {
    static byte[] decode(String key) {
        String normalized = key.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z2-7]+={0,6}")) throw new IllegalArgumentException();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int bits = 0, buffer = 0;
        for (char c : normalized.replace("=", "").toCharArray()) {
            buffer = (buffer << 5) | "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c);
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                bytes.write((buffer >> bits) & 255);
            }
        }
        if (bytes.size() == 0 || bits >= 5 || (buffer & ((1 << bits) - 1)) != 0)
            throw new IllegalArgumentException();
        return bytes.toByteArray();
    }

    static String code(byte[] key, long now, int period, int digits, String algorithm) throws Exception {
        Mac mac = Mac.getInstance("Hmac" + algorithm);
        mac.init(new SecretKeySpec(key, mac.getAlgorithm()));
        byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(now / period).array());
        int offset = hash[hash.length - 1] & 15;
        int value = ByteBuffer.wrap(hash, offset, 4).getInt() & Integer.MAX_VALUE;
        return String.format(Locale.ROOT, "%0" + digits + "d", value % (digits == 8 ? 100000000 : 1000000));
    }
}
