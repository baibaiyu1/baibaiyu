package com.gmd.faq.server.main.engine.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmd.faq.server.main.engine.tool.builtins.WebPageFetchTool;
import io.agentscope.core.message.ToolResultState;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WebPageReaderTest {
    @Test void extractsChineseOfficialStyleArticleAndPreservesKnownMetadata() throws Exception {
        String html = """
                <html><head><meta charset="GB18030"><title>防汛应急通报</title>
                <meta name="PubDate" content="2026-10-26 09:00"></head>
                <body><header>站点导航</header><nav>主页栏目</nav><div class="TRS_Editor">
                <p>已启动防汛应急响应。</p><p>请关注属地官方预警信息。</p></div>
                <script>secretScript()</script><footer>版权备案</footer></body></html>
                """;
        var reader = reader(chain -> response(chain, "text/html", html.getBytes(Charset.forName("GB18030"))));
        var page = reader.read("https://www.mem.gov.cn/news/1", null);
        assertEquals("防汛应急通报", page.title());
        assertEquals("2026-10-26 09:00", page.publishedAt());
        assertTrue(page.text().contains("已启动防汛应急响应。"));
        assertTrue(page.text().contains("请关注属地官方预警信息。"));
        assertFalse(page.text().contains("导航")); assertFalse(page.text().contains("secretScript"));
        assertFalse(page.text().contains("版权备案")); assertFalse(page.truncated());
    }

    @Test void boundsOutputAndLeavesMissingDatesUnknown() throws Exception {
        var reader = reader(chain -> response(chain, "text/html; charset=utf-8",
                ("<article><p>防灾资料" + "正文".repeat(1000) + "</p></article>").getBytes(StandardCharsets.UTF_8)));
        var page = reader.read("https://www.mem.gov.cn/news/2", 500);
        assertEquals(500, page.text().length()); assertTrue(page.truncated()); assertNull(page.publishedAt());
        var plain = reader(chain -> response(chain, "text/plain; charset=utf-8", "应急物资清单".getBytes(StandardCharsets.UTF_8)))
                .read("https://www.mem.gov.cn/file.txt", null);
        assertEquals("应急物资清单", plain.text()); assertNull(plain.publishedAt());
    }

    @Test void blocksLocalMetadataAndNonHttpTargetsIncludingRedirects() throws Exception {
        for (String host : List.of("127.0.0.1", "0.0.0.0", "169.254.169.254", "10.0.0.1", "100.64.0.1", "::1", "fd00::1")) {
            assertThrows(UnknownHostException.class, () -> WebPageReader.resolvePublicHost(host));
        }
        for (String url : List.of("file:///etc/passwd", "https://name:secret@example.com/", "http://example.com:8080/", "data:text/plain,hello")) {
            assertThrows(IllegalArgumentException.class, () -> WebPageReader.validateUrl(url));
        }
        assertThrows(IOException.class, () -> new WebPageReader().read("http://127.0.0.1/private", null));
        AtomicInteger calls = new AtomicInteger();
        var client = new OkHttpClient.Builder().dns(host -> host.equals("www.mem.gov.cn")
                ? List.of(InetAddress.getByName("8.8.8.8")) : WebPageReader.resolvePublicHost(host))
                .addInterceptor(chain -> {
                    calls.incrementAndGet();
                    return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("test")
                            .header("Location", "http://169.254.169.254/latest/meta-data/")
                            .body(ResponseBody.create("", MediaType.get("text/html"))).build();
                }).build();
        assertThrows(IOException.class, () -> new WebPageReader(client).read("https://www.mem.gov.cn/news", null));
        assertEquals(1, calls.get(), "重定向到内网必须在第二次请求前拒绝");
    }

    @Test void followsRelativeRedirectsWithoutForwardingCredentialsAndLimitsLoops() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var reader = reader(chain -> {
            assertNull(chain.request().header("Authorization")); assertNull(chain.request().header("Cookie"));
            if (calls.incrementAndGet() == 1) {
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("test")
                        .header("Location", "/article").body(ResponseBody.create("", MediaType.get("text/html"))).build();
            }
            return response(chain, "text/html", "<article>公开应急资料</article>".getBytes(StandardCharsets.UTF_8));
        });
        assertEquals("https://www.mem.gov.cn/article", reader.read("https://www.mem.gov.cn/start", null).url());
        assertEquals(2, calls.get());
        calls.set(0);
        var loop = reader(chain -> {
            calls.incrementAndGet();
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("test")
                    .header("Location", "/loop").body(ResponseBody.create("", MediaType.get("text/html"))).build();
        });
        assertThrows(IOException.class, () -> loop.read("https://www.mem.gov.cn/loop", null));
        assertEquals(4, calls.get());
    }

    @Test void oversizedUnsupportedAndEmptyPagesRemainToolErrors() {
        for (String contentType : List.of("application/pdf", "image/png", "text/html")) {
            byte[] bytes = " ".repeat(2 * 1024 * 1024 + 1).getBytes(StandardCharsets.UTF_8);
            var tool = new WebPageFetchTool(reader(chain -> response(chain, contentType, bytes)), new ObjectMapper());
            assertEquals(ToolResultState.ERROR, tool.fetch("https://www.mem.gov.cn/file", null).getState());
        }
        var tool = new WebPageFetchTool(reader(chain -> response(chain, "text/html", "<script>onlyJs()</script>".getBytes(StandardCharsets.UTF_8))), new ObjectMapper());
        assertEquals(ToolResultState.ERROR, tool.fetch("https://www.mem.gov.cn/empty", null).getState());
        assertEquals(ToolResultState.ERROR, tool.fetch("https://www.mem.gov.cn/empty", 499).getState());
    }

    private static WebPageReader reader(Interceptor interceptor) {
        return new WebPageReader(new OkHttpClient.Builder().dns(host -> List.of(InetAddress.getByName("8.8.8.8")))
                .addInterceptor(interceptor).build());
    }

    private static Response response(Interceptor.Chain chain, String type, byte[] body) {
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("test")
                .header("Content-Type", type).body(ResponseBody.create(body, MediaType.get(type))).build();
    }
}
