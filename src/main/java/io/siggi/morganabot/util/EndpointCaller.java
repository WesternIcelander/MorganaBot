package io.siggi.morganabot.util;

import com.google.gson.Gson;
import com.google.gson.JsonElement;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class EndpointCaller {
    private final HeaderAdder headerAdder;
    private final Gson gson;

    public EndpointCaller(HeaderAdder headerAdder, Gson gson) {
        this.headerAdder = headerAdder;
        this.gson = gson;
    }

    public HttpURLConnection connect(String endpoint) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestProperty("User-Agent", "Morgana");
        if (headerAdder != null) headerAdder.addHeaders(connection);
        return connection;
    }

    public HttpURLConnection get(String endpoint, Map<String, String> getData) throws IOException {
        if (getData != null) {
            if (endpoint.contains("?")) {
                endpoint += "&" + urlEncodeMap(getData);
            } else {
                endpoint += "?" + urlEncodeMap(getData);
            }
        }
        return connect(endpoint);
    }

    public HttpURLConnection post(String endpoint, String contentType, byte[] data) throws IOException {
        HttpURLConnection connection = connect(endpoint);
        connection.setRequestProperty("Content-Type", contentType);
        connection.setDoOutput(true);
        connection.setRequestMethod("POST");
        connection.setFixedLengthStreamingMode(data.length);
        connection.setRequestProperty("Content-Length", Integer.toString(data.length));
        connection.getOutputStream().write(data);
        return connection;
    }

    public HttpURLConnection post(String endpoint, Object postData) throws IOException {
        return post(endpoint, "application/json", gson.toJson(postData).getBytes(StandardCharsets.UTF_8));
    }

    public HttpURLConnection post(String endpoint, JsonElement postData) throws IOException {
        return post(endpoint, "application/json", postData.toString().getBytes(StandardCharsets.UTF_8));
    }

    public HttpURLConnection post(String endpoint, Map<String, String> postData) throws IOException {
        return post(endpoint, "application/x-www-form-urlencoded", urlEncodeMap(postData).getBytes(StandardCharsets.UTF_8));
    }

    private String urlEncodeMap(Map<String, String> postData) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : postData.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || value == null) continue;
            if (sb.length() != 0) {
                sb.append("&");
            }
            sb.append(urlEncode(key));
            sb.append("=");
            sb.append(urlEncode(value));
        }
        return sb.toString();
    }

    private String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    @FunctionalInterface
    public interface HeaderAdder {
        void addHeaders(HttpURLConnection connection) throws IOException;
    }
}
