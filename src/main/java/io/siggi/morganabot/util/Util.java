package io.siggi.morganabot.util;


import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

public class Util {
    private Util() {
    }

    public static File getDataRoot() {
        String dataroot = System.getProperty("dataroot");
        if (dataroot != null) {
            return new File(dataroot);
        }
        return new File("data").getAbsoluteFile();
    }

    public static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[4096];
        int c;
        while ((c = in.read(buffer, 0, buffer.length)) != -1) {
            out.write(buffer, 0, c);
        }
    }

    public static byte[] readFully(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(in, out);
        return out.toByteArray();
    }

    public static String urlEncode(String str) {
        try {
            return URLEncoder.encode(str, "UTF-8");
        } catch (UnsupportedEncodingException ex) {
            return null;
        }
    }

    public static byte[] readFile(File file) throws IOException {
        byte[] data = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int pos = 0;
            while (pos < data.length) {
                int c = in.read(data, pos, data.length - pos);
                if (c == -1) {
                    return Arrays.copyOf(data, pos);
                }
                pos += c;
            }
        }
        return data;
    }

    public static void writeFile(File file, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
    }

    public static UUID uuidFromString(String uuid) {
        return UUID.fromString(
                uuid.replace("-","")
                        .replaceAll(
                                "([0-9A-Fa-f]{8})([0-9A-Fa-f]{4})([0-9A-Fa-f]{4})([0-9A-Fa-f]{4})([0-9A-Fa-f]{12})",
                                "$1-$2-$3-$4-$5"
                        )
        );
    }

    private static final char[] randomStringCharset = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    public static String randomString(int length) {
        SecureRandom random = new SecureRandom();
        char[] randomString = new char[length];
        for (int i = 0; i < length; i++) {
            randomString[i] = randomStringCharset[random.nextInt(randomStringCharset.length)];
        }
        return new String(randomString);
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xff;
            if (b < 16) {
                sb.append("0");
            }
            sb.append(Integer.toString(b, 16));
        }
        return sb.toString();
    }

    public static JsonElement parseJson(HttpURLConnection connection) throws IOException {
        return JsonParser.parseReader(new InputStreamReader(connection.getInputStream()));
    }

    public static String markdownEscape(String text) {
        return text
                .replace("_", "\\_")
                .replace("~", "\\~")
                .replace("*", "\\*");
    }
}
