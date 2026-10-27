package com.gmd.faq.server.main.engine.tool;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/** 读取公开网页；实际连接 DNS 和每次重定向均受公网范围限制。 */
@Component
public final class WebPageReader {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private final OkHttpClient client;

    public WebPageReader() {
        this(new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dns(WebPageReader::resolvePublicHost)
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS).build());
    }

    WebPageReader(OkHttpClient client) { this.client = client; }

    public Page read(String url, Integer maxChars) throws IOException {
        int limit = maxChars == null ? 12000 : maxChars;
        if (limit < 500 || limit > 30000) throw new IllegalArgumentException("正文长度必须为 500～30000 字");
        URI uri = validateUrl(url);
        for (int redirect = 0; redirect <= 3; redirect++) {
            // OkHttp 对数字 IP 可能跳过自定义 DNS；连接前也校验一次，每个重定向目标都重新检查。
            requirePublicAddresses(client.dns().lookup(uri.getHost()));
            try (Response response = client.newCall(new Request.Builder().url(uri.toASCIIString())
                    .header("User-Agent", "GMD-FAQ-WebReader/1.0")
                    .header("Accept", "text/html, application/xhtml+xml, text/plain")
                    .get().build()).execute()) {
                if (response.code() >= 300 && response.code() < 400) {
                    String location = response.header("Location");
                    if (location == null) throw new IOException("网页重定向缺少目标地址");
                    uri = validateUrl(uri.resolve(location).toString());
                    continue;
                }
                if (!response.isSuccessful() || response.body() == null) throw new IOException("网页无法访问");
                String type = response.header("Content-Type", "").split(";", 2)[0].strip();
                if (!List.of("text/html", "application/xhtml+xml", "text/plain").contains(type.toLowerCase(java.util.Locale.ROOT))) {
                    throw new IllegalArgumentException("只支持 HTML 和纯文本网页；PDF、图片及其他文件请使用文件读取能力");
                }
                if (response.body().contentLength() > MAX_BYTES) throw new IOException("网页超过大小限制");
                byte[] bytes = response.body().byteStream().readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) throw new IOException("网页超过大小限制");
                Charset charset = response.body().contentType() == null ? null : response.body().contentType().charset();
                String text;
                String title = "";
                String publishedAt = null;
                if ("text/plain".equalsIgnoreCase(type)) {
                    text = new String(bytes, charset == null ? java.nio.charset.StandardCharsets.UTF_8 : charset).strip();
                } else {
                    Document document = Jsoup.parse(new ByteArrayInputStream(bytes), charset == null ? null : charset.name(), uri.toString());
                    title = document.title();
                    Element published = document.selectFirst("meta[property=article:published_time],meta[name=publishdate],"
                            + "meta[name=PubDate],meta[name=publish_date],meta[name=DC.date.issued],meta[name=发布日期]");
                    if (published != null && !published.attr("content").isBlank()) publishedAt = published.attr("content");
                    document.select("script,style,noscript,nav,header,footer,aside,form,iframe,svg,button").remove();
                    Element body = document.select("article,main,.TRS_Editor,.TRS_UEDITOR,#UCAP-CONTENT,#zoom,.article-content,.article_content")
                            .stream().max(java.util.Comparator.comparingInt(element -> element.text().length())).orElse(document.body());
                    body.select("p,div,section,h1,h2,h3,h4,li,blockquote,pre,tr").forEach(element -> element.prependText("\n"));
                    text = body.wholeText().replaceAll("[\\t\\x0B\\f\\r ]+", " ")
                            .replaceAll(" *\\n *", "\n").replaceAll("\\n{3,}", "\n\n").strip();
                }
                if (text.isBlank()) throw new IOException("网页未提取到正文");
                boolean truncated = text.length() > limit;
                return new Page(uri.toString(), truncate(title, 500), truncate(publishedAt, 100),
                        truncated ? text.substring(0, limit) : text, truncated, Instant.now().toString());
            }
        }
        throw new IOException("网页重定向次数过多");
    }

    static URI validateUrl(String url) {
        if (url == null || url.isBlank() || url.length() > 4096) throw new IllegalArgumentException("网页地址不能为空或超过 4096 字");
        URI uri;
        try { uri = URI.create(url.strip()); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("网页地址格式不正确"); }
        if ((!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443) {
            throw new IllegalArgumentException("网页地址仅支持无凭据、使用标准端口的 HTTP/HTTPS URL");
        }
        return uri;
    }

    static List<InetAddress> resolvePublicHost(String host) throws UnknownHostException {
        List<InetAddress> addresses = Arrays.asList(InetAddress.getAllByName(host));
        requirePublicAddresses(addresses);
        return addresses;
    }

    private static void requirePublicAddresses(List<InetAddress> addresses) throws UnknownHostException {
        if (addresses.isEmpty() || addresses.stream().anyMatch(address -> !ToolImageDownloader.isPublicAddress(address))) {
            throw new UnknownHostException("网页地址不在允许的公网范围内");
        }
    }

    private static String truncate(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }

    public record Page(String url, String title, String publishedAt, String text, boolean truncated, String retrievedAt) {}
}
