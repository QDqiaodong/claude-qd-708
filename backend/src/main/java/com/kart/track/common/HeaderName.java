package com.kart.track.common;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * 请求头里的中文姓名解码。
 *
 * <p>前端 axios 对 {@code X-Operator-Name} 做 {@code encodeURIComponent}；
 * 未编码（如 curl 直发 UTF-8）时 Tomcat 按 ISO-8859-1 读头，这里再按 UTF-8 还原，两种都兼容。
 */
public final class HeaderName {

    private HeaderName() {
    }

    public static String decode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        try {
            if (v.indexOf('%') >= 0) {
                return URLDecoder.decode(v, StandardCharsets.UTF_8);
            }
            return new String(v.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return v;
        }
    }
}
