package com.gmd.faq.server.main.engine.tool;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** 有界图片下载；DNS 校验也用于实际连接，禁止把工具 URL 当成任意内网代理。 */
final class ToolImageDownloader {
    private final Set<String> allowedHosts;
    private final OkHttpClient client;

    ToolImageDownloader() {
        this(Arrays.stream(System.getenv().getOrDefault("TOOL_IMAGE_ALLOWED_HOSTS", "").split(","))
                .map(String::trim).filter(s -> !s.isBlank())
                .map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet()));
    }

    ToolImageDownloader(Set<String> allowedHosts) {
        this.allowedHosts = allowedHosts;
        this.client = new OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
                .dns(this::resolve).followRedirects(false).followSslRedirects(false)
                .connectTimeout(3, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS).build();
    }

    byte[] download(String source, int maxBytes) throws IOException {
        URI uri;
        try { uri = URI.create(source); }
        catch (IllegalArgumentException e) { throw new IOException("图片地址无效"); }
        for (int redirect = 0; redirect <= 3; redirect++) {
            validate(uri);
            try (Response response = client.newCall(new Request.Builder().url(uri.toString()).get().build()).execute()) {
                if (response.code() >= 300 && response.code() < 400) {
                    String location = response.header("Location");
                    if (location == null) break;
                    try { uri = uri.resolve(location); }
                    catch (IllegalArgumentException e) { throw new IOException("图片重定向地址无效"); }
                    continue;
                }
                if (!response.isSuccessful() || response.body() == null) {
                    throw new IOException("图片下载失败");
                }
                if (response.body().contentLength() > maxBytes) throw new IOException("图片超过单张大小限制");
                byte[] bytes = response.body().byteStream().readNBytes(maxBytes + 1);
                if (bytes.length > maxBytes) throw new IOException("图片超过单张大小限制");
                return bytes;
            }
        }
        throw new IOException("图片重定向次数过多");
    }

    private void validate(URI uri) throws IOException {
        if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getRawUserInfo() != null) {
            throw new IOException("图片地址仅支持无凭据的 HTTP/HTTPS URL");
        }
        resolve(uri.getHost());
    }

    private List<InetAddress> resolve(String hostname) throws UnknownHostException {
        List<InetAddress> addresses = Arrays.asList(InetAddress.getAllByName(hostname));
        if (!allowedHosts.contains(hostname.toLowerCase(Locale.ROOT))
                && addresses.stream().anyMatch(address -> !isPublicAddress(address))) {
            throw new UnknownHostException("图片地址不在允许的网络范围内");
        }
        return addresses;
    }

    static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        int first = bytes[0] & 0xff;
        if (bytes.length == 16) return (first & 0xe0) == 0x20; // 仅全球单播 IPv6
        int second = bytes[1] & 0xff;
        return first != 0 && first < 224 && first != 127
                && !(first == 100 && second >= 64 && second <= 127)
                && !(first == 198 && (second == 18 || second == 19));
    }
}
