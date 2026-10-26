package com.gmd.faq.server.main.engine.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmd.faq.server.main.engine.mcp.McpToolResultConverter;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.modelcontextprotocol.spec.McpSchema;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ToolResultImageSupportTest {
    @Test void normalizingImagesKeepsExecutionFailureState() {
        var failed = ToolResultBlock.of("failed-image", "chart", ImageBlock.builder()
                .source(new Base64Source("image/png", "bad!")).build())
                .withState(io.agentscope.core.message.ToolResultState.ERROR);
        assertEquals(io.agentscope.core.message.ToolResultState.ERROR, support.normalize(failed, workspace).getState());
    }
    private Path workspace;
    private final ToolResultImageSupport support = new ToolResultImageSupport();

    @BeforeEach void setUp() throws Exception { workspace = Files.createTempDirectory("tool-images-"); }
    @AfterEach void tearDown() throws Exception {
        try (var files = Files.walk(workspace)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }

    @Test void testNativeMcpMixedResultRetainsEveryBlockAndPersistsImageReferences() throws Exception {
        byte[] first = png(0xffff0000);
        byte[] second = png(0xff0000ff);
        var mcp = new McpSchema.CallToolResult(List.of(
                new McpSchema.TextContent("截图结果"),
                new McpSchema.ImageContent(null, Base64.getEncoder().encodeToString(first), "image/png"),
                new McpSchema.ImageContent(null, Base64.getEncoder().encodeToString(second), "image/png"),
                new McpSchema.TextContent("两张截图已完成")), false);
        ToolResultBlock result = support.normalize(McpToolResultConverter.convert(mcp).withIdAndName("call-1", "screenshot"), workspace);
        ToolResultPresentation presentation = ToolResultPresentation.from(result);
        assertEquals("截图结果\n两张截图已完成", presentation.text());
        assertEquals(2, presentation.images().size());
        assertEquals(2, result.getOutput().stream().filter(ImageBlock.class::isInstance).count());
        assertTrue(java.util.Arrays.equals(first, Files.readAllBytes(workspace.resolve(presentation.images().getFirst().filePath()))));
        String history = new ObjectMapper().writeValueAsString(presentation);
        assertFalse(history.contains(Base64.getEncoder().encodeToString(first)));
        assertSame(result, support.normalize(result, workspace));
    }

    @Test void testJsonBase64AndWorkspacePathsBecomeNativeImagesWithoutPayloadInText() throws Exception {
        byte[] bytes = png(0xff00ff00);
        Files.write(workspace.resolve("chart.png"), bytes);
        String base64 = Base64.getEncoder().encodeToString(bytes);
        String json = new ObjectMapper().writeValueAsString(Map.of("message", "图表", "images", List.of(
                Map.of("mimeType", "image/png", "data", base64, "name", "图一"),
                Map.of("filePath", "chart.png", "name", "图二"))));
        ToolResultBlock result = support.normalize(ToolResultBlock.of("json-call", "chart", TextBlock.builder().text(json).build()), workspace);
        var presentation = ToolResultPresentation.from(result);
        assertEquals(2, presentation.images().size());
        assertFalse(presentation.text().contains(base64));
        assertTrue(presentation.text().contains(".tool-results/"));
        assertEquals(2, result.getOutput().stream().filter(ImageBlock.class::isInstance).count());
        // 同一次调用中相同内容只保存一份文件，但完整保留返回内容的顺序。
        assertEquals(presentation.images().getFirst().filePath(), presentation.images().getLast().filePath());
        assertNull(presentation.images().getFirst().sourcePath(), "Base64 不应出现在展示元数据中");
        assertEquals("chart.png", presentation.images().getLast().sourcePath(), "正文引用应指向保存的图片副本");
    }

    @Test void testOverwrittenSourceDoesNotChangePreviouslyReturnedImage() throws Exception {
        byte[] first = png(0xffff0000);
        Files.write(workspace.resolve("chart.png"), first);
        ToolResultBlock source = ToolResultBlock.of("call", "chart", TextBlock.builder().text("{\"imageUrl\":\"chart.png\"}").build());
        String oldPath = ToolResultPresentation.from(support.normalize(source, workspace)).images().getFirst().filePath();
        Files.write(workspace.resolve("chart.png"), png(0xff0000ff));
        String newPath = ToolResultPresentation.from(support.normalize(source, workspace)).images().getFirst().filePath();
        assertFalse(oldPath.equals(newPath));
        assertTrue(java.util.Arrays.equals(first, Files.readAllBytes(workspace.resolve(oldPath))));
    }

    @Test void testImageNamesDoNotChangeImmutableReferencesOrBytes() throws Exception {
        byte[] bytes = png(0xff00ff00);
        Files.write(workspace.resolve("chart.png"), bytes);
        String json = new ObjectMapper().writeValueAsString(Map.of("images", List.of(
                Map.of("filePath", "chart.png", "name", "device_snapshot.png"),
                Map.of("filePath", "chart.png", "name", "folder/设备截图.png"),
                Map.of("filePath", "chart.png", "name", "GMD9300LED"),
                Map.of("filePath", "chart.png", "fileName", "9300.png"),
                Map.of("filePath", "chart.png", "filename", "product.png"),
                Map.of("filePath", "chart.png", "name", " ", "caption", "Product diagram"),
                Map.of("filePath", "chart.png", "name", " \n "),
                Map.of("filePath", "chart.png"))));
        var result = support.normalize(ToolResultBlock.of("names", "screenshot", TextBlock.builder().text(json).build()), workspace);
        var images = ToolResultPresentation.from(result).images();
        assertEquals(List.of("device_snapshot.png", "设备截图.png", "GMD9300LED.png", "9300.png", "product.png",
                "Product diagram.png", "图片.png", "图片.png"), images.stream().map(ToolResultPresentation.Image::fileName).toList());
        assertEquals(1, images.stream().map(ToolResultPresentation.Image::filePath).distinct().count());
        assertArrayEquals(bytes, Files.readAllBytes(workspace.resolve(images.getFirst().filePath())));
    }

    @Test void testExplicitImagePathTakesPrecedenceOverUnrelatedData() throws Exception {
        Files.write(workspace.resolve("chart.png"), png(0xffff0000));
        var result = support.normalize(ToolResultBlock.of("call", "chart", TextBlock.builder()
                .text("{\"images\":[{\"filePath\":\"chart.png\",\"data\":\"caption\"}]}").build()), workspace);
        assertNull(ToolResultPresentation.from(result).images().getFirst().error());
        assertEquals(1, result.getOutput().stream().filter(ImageBlock.class::isInstance).count());
    }

    @Test void testInvalidImageAndPathTraversalProduceCardsWithoutBreakingOtherResults() {
        var result = support.normalize(ToolResultBlock.of("call", "chart", List.of(
                TextBlock.builder().text("正常文本").build(),
                ImageBlock.builder().source(new Base64Source("image/png", "bad!")).build(),
                TextBlock.builder().text("{\"images\":[\"../outside.png\"]}").build())), workspace);
        var presentation = ToolResultPresentation.from(result);
        assertEquals(2, presentation.images().size());
        assertTrue(presentation.images().stream().allMatch(image -> "图片".equals(image.fileName())));
        assertNotNull(presentation.images().getFirst().error());
        assertNotNull(presentation.images().getLast().error());
        assertEquals(0, result.getOutput().stream().filter(ImageBlock.class::isInstance).count());
        assertTrue(presentation.text().contains("正常文本"));
    }

    @Test void testStructuredMcpResultsArePreservedAndNormalized() throws Exception {
        String base64 = Base64.getEncoder().encodeToString(png(0xffff0000));
        var mcp = new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("完成")), false,
                Map.of("images", List.of(Map.of("base64", base64, "mimeType", "image/png"))), Map.of());
        var result = support.normalize(McpToolResultConverter.convert(mcp).withIdAndName("mcp-json", "generate"), workspace);
        assertEquals(1, ToolResultPresentation.from(result).images().size());
        assertTrue(ToolResultPresentation.from(result).text().startsWith("完成\n"));
    }

    @Test void testRemoteDownloadHasLimitsAndRejectsPrivateAddressesByDefault() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] png = png(0xff00ff00);
        server.createContext("/image", exchange -> {
            exchange.sendResponseHeaders(200, png.length);
            exchange.getResponseBody().write(png); exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/image");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            ToolImageDownloader trusted = new ToolImageDownloader(Set.of("127.0.0.1"));
            assertTrue(java.util.Arrays.equals(png, trusted.download(url + "/redirect", 1024)));
            try { trusted.download(url + "/image", 10); fail("应拒绝过大图片"); }
            catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("大小限制")); }
            try { new ToolImageDownloader(Set.of()).download(url + "/image", 1024); fail("应拒绝默认内网访问"); }
            catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("网络范围")); }
            assertFalse(ToolImageDownloader.isPublicAddress(InetAddress.getByName("169.254.169.254")));
            assertFalse(ToolImageDownloader.isPublicAddress(InetAddress.getByName("100.64.0.1")));
            assertFalse(ToolImageDownloader.isPublicAddress(InetAddress.getByName("fd00::1")));
        } finally { server.stop(0); }
    }

    public static byte[] png(int color) throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, color);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }
}
