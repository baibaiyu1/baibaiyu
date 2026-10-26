package com.gmd.faq.server.main.engine.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gmd.faq.server.main.common.consts.SysConst;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

/** 归一化图片返回：不可变文件用于回显，Base64 ImageBlock 用于后续模型推理。 */
@Component
public class ToolResultImageSupport {
    public static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_IMAGES = 20;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Tika TIKA = new Tika();
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/png", "png", "image/jpeg", "jpg", "image/gif", "gif",
            "image/webp", "webp", "image/bmp", "bmp");
    private static final Set<String> SOURCE_FIELDS = Set.of(
            "url", "uri", "src", "imageUrl", "image_url", "downloadUrl", "filePath", "path",
            "base64", "imageBase64", "data", "blob");
    private static final Set<String> IMAGE_FIELDS = Set.of("image", "images", "imageUrl", "imageUrls",
            "image_url", "image_urls", "imageBase64");
    private static final Object[] LOCKS = Stream.generate(Object::new).limit(64).toArray();
    private final ToolImageDownloader downloader = new ToolImageDownloader();

    public ToolResultBlock normalize(ToolResultBlock result, Path workspace) {
        if (result == null || result.getMetadata().containsKey(ToolResultPresentation.METADATA_KEY)) return result;
        State state = new State(workspace.toAbsolutePath().normalize(), result.getId());
        List<ContentBlock> output = new ArrayList<>();
        for (ContentBlock block : result.getOutput()) {
            if (block instanceof ImageBlock image) {
                String source = image.getSource() instanceof Base64Source base64
                        ? "data:" + base64.getMediaType() + ";base64," + base64.getData()
                        : image.getSource() instanceof URLSource url ? url.getUrl() : "";
                addImage(source, null, state, output);
            } else if (block instanceof TextBlock text) {
                List<ContentBlock> extracted = new ArrayList<>();
                String value = normalizeText(text.getText(), state, extracted);
                output.add(TextBlock.builder().text(value == null ? "" : value).build());
                output.addAll(extracted);
            } else output.add(block);
        }
        if (state.images.isEmpty()) return result;
        Map<String, Object> metadata = new HashMap<>(result.getMetadata());
        metadata.put(ToolResultPresentation.METADATA_KEY, state.images);
        return ToolResultBlock.of(result.getId(), result.getName(), output, metadata).withState(result.getState());
    }

    private String normalizeText(String value, State state, List<ContentBlock> output) {
        if (value == null || value.isBlank()) return value;
        try {
            JsonNode json = MAPPER.readTree(value);
            int before = state.images.size();
            JsonNode rewritten = rewrite(json, false, 0, state, output);
            return state.images.size() == before ? value : MAPPER.writeValueAsString(rewritten);
        } catch (IOException ignored) {
            if (looksLikeImage(value.trim())) {
                ToolResultPresentation.Image image = addImage(value.trim(), null, state, output);
                return image.error() == null ? "工具返回图片：" + image.filePath() : image.error();
            }
            return value;
        }
    }

    private JsonNode rewrite(JsonNode node, boolean imageHint, int depth, State state, List<ContentBlock> output) {
        if (node == null || depth > 8) return node;
        if (node.isTextual()) {
            String source = node.asText();
            if (imageHint || looksLikeImage(source)) {
                return MAPPER.valueToTree(addImage(source, null, state, output));
            }
            return node;
        }
        if (node.isArray()) {
            var rewritten = MAPPER.createArrayNode();
            node.forEach(item -> rewritten.add(rewrite(item, imageHint, depth + 1, state, output)));
            return rewritten;
        }
        if (!node.isObject()) return node;
        String mime = node.path("mimeType").asText(node.path("mediaType").asText(node.path("mime_type").asText("")));
        String sourceField = sourceFieldOf(node);
        String source = sourceField == null ? null : node.path(sourceField).asText();
        boolean explicitImage = imageHint || "image".equals(node.path("type").asText())
                || mime.startsWith("image/") || node.has("imageBase64") || node.has("imageUrl") || node.has("image_url");
        if (source != null && (explicitImage || looksLikeImage(source))) {
            if (!source.startsWith("data:") && (Set.of("base64", "imageBase64", "blob").contains(sourceField)
                    || (sourceField.equals("data") && explicitImage && !looksLikeImage(source)
                    && !source.startsWith("https://") && !source.startsWith("http://")))) {
                source = "data:" + (mime.isBlank() ? "image/png" : mime) + ";base64," + source;
            }
            ToolResultPresentation.Image image = addImage(source, imageNameOf(node), state, output);
            ObjectNode replacement = ((ObjectNode) node).deepCopy();
            SOURCE_FIELDS.forEach(replacement::remove);
            replacement.setAll((ObjectNode) MAPPER.valueToTree(image));
            return replacement;
        }
        ObjectNode rewritten = ((ObjectNode) node).deepCopy();
        node.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            // 只识别约定的展示字段与 MCP content/resource，避免扫描无关的参数或正文字符串。
            if (IMAGE_FIELDS.contains(key) || Set.of("content", "resource", "resources", "files", "result", "structuredContent").contains(key)
                    || (key.equals("data") && entry.getValue().isContainerNode())) {
                rewritten.set(key, rewrite(entry.getValue(), IMAGE_FIELDS.contains(key), depth + 1, state, output));
            }
        });
        return rewritten;
    }

    private String sourceFieldOf(JsonNode node) {
        for (String field : List.of("base64", "imageBase64", "blob", "filePath", "path", "imageUrl", "image_url", "url", "src", "downloadUrl", "uri", "data")) {
            if (node.path(field).isTextual()) return field;
        }
        return null;
    }

    private String imageNameOf(JsonNode node) {
        for (String field : List.of("name", "fileName", "filename", "caption")) {
            if (node.path(field).isTextual() && !node.path(field).asText().isBlank()) return node.path(field).asText();
        }
        return null;
    }

    private boolean looksLikeImage(String source) {
        if (source == null || source.isBlank() || source.contains("\n")) return false;
        if (source.startsWith("data:image/")) return true;
        return source.matches("(?i)(?:https?://[^\\s]+|[^\\s]+)\\.(?:png|jpe?g|gif|webp|bmp)(?:[?#].*)?");
    }

    private ToolResultPresentation.Image addImage(String source, String name, State state, List<ContentBlock> output) {
        ToolResultPresentation.Image image;
        try {
            if (state.images.size() >= MAX_IMAGES) throw new IOException("单次工具返回图片数量超过限制");
            byte[] bytes = readImage(source, state.workspace);
            String mime = TIKA.detect(bytes);
            String extension = EXTENSIONS.get(mime);
            if (extension == null) throw new IOException("返回内容不是受支持的 PNG/JPEG/GIF/WebP/BMP 图片");
            String relative = ".tool-results/" + hash(state.toolCallId.getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 16)
                    + "/" + hash(bytes) + "." + extension;
            Path target = checkedPath(state.workspace, relative);
            synchronized (LOCKS[Math.floorMod(state.workspace.hashCode(), LOCKS.length)]) {
                if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    long capacity = (long) (Double.parseDouble(System.getenv().getOrDefault("WORKSPACE_CAPACITY_MB",
                            String.valueOf(SysConst.WORKSPACE_CAPACITY_MB))) * 1024 * 1024);
                    if (workspaceSize(state.workspace) + bytes.length > capacity) throw new IOException("工作空间容量超出限制");
                    Files.createDirectories(target.getParent());
                    checkedPath(state.workspace, relative);
                    Path temporary = Files.createTempFile(target.getParent(), "image-", ".tmp");
                    try {
                        Files.write(temporary, bytes);
                        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                    } finally { Files.deleteIfExists(temporary); }
                }
            }
            String fileName = imageName(name, extension);
            image = new ToolResultPresentation.Image(relative, fileName, extension, mime, (long) bytes.length, null,
                    source.startsWith("data:") ? null : source);
            output.add(ImageBlock.builder().source(new Base64Source(mime, Base64.getEncoder().encodeToString(bytes))).build());
        } catch (Exception e) {
            String error = e instanceof IOException ? e.getMessage() : "图片内容无效或无法保存";
            image = new ToolResultPresentation.Image(null, imageName(name, null),
                    null, null, null, error);
            output.add(TextBlock.builder().text("[工具图片未能加载：" + error + "]").build());
        }
        state.images.add(image);
        return image;
    }

    /** 保留工具提供的名称，缺省使用“图片”；内部不可变路径不受展示名称影响。 */
    private String imageName(String name, String extension) {
        String title = name == null ? "" : name.replace('\\', '/');
        title = title.substring(title.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").strip();
        if (title.isBlank() || title.equals(".") || title.equals("..")) title = "图片";
        String lowerTitle = title.toLowerCase(Locale.ROOT);
        if (extension != null && !lowerTitle.endsWith("." + extension)
                && !(extension.equals("jpg") && lowerTitle.endsWith(".jpeg"))) title += "." + extension;
        return title;
    }

    private byte[] readImage(String source, Path workspace) throws IOException {
        if (source == null || source.isBlank()) throw new IOException("图片地址为空");
        if (source.startsWith("data:")) {
            int separator = source.indexOf(',');
            if (separator < 0 || !source.substring(0, separator).endsWith(";base64")) throw new IOException("图片 Base64 格式无效");
            String data = source.substring(separator + 1);
            if (data.length() > (MAX_IMAGE_BYTES + 2L) / 3 * 4) throw new IOException("图片超过单张大小限制");
            byte[] bytes;
            try { bytes = Base64.getDecoder().decode(data); }
            catch (IllegalArgumentException e) { throw new IOException("图片 Base64 格式无效"); }
            if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) throw new IOException("图片为空或超过单张大小限制");
            return bytes;
        }
        if (source.startsWith("https://") || source.startsWith("http://")) return downloader.download(source, MAX_IMAGE_BYTES);
        Path file = checkedPath(workspace, source);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("工作空间图片不存在");
        if (Files.size(file) > MAX_IMAGE_BYTES) throw new IOException("图片超过单张大小限制");
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(MAX_IMAGE_BYTES + 1);
            if (bytes.length > MAX_IMAGE_BYTES) throw new IOException("图片超过单张大小限制");
            return bytes;
        }
    }

    public static Path checkedPath(Path workspace, String path) throws IOException {
        Path target = workspace.resolve(path).normalize();
        if (!target.startsWith(workspace)) throw new IOException("图片路径不允许越出当前工作空间");
        Path current = target;
        while (current != null) {
            if (Files.isSymbolicLink(current)) throw new IOException("图片路径不允许使用符号链接");
            current = current.getParent();
        }
        return target;
    }

    private long workspaceSize(Path workspace) throws IOException {
        if (!Files.exists(workspace)) return 0;
        try (Stream<Path> files = Files.walk(workspace)) {
            long total = 0;
            for (Path file : files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) total += Files.size(file);
            return total;
        }
    }

    private String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static class State {
        final Path workspace;
        final String toolCallId;
        final List<ToolResultPresentation.Image> images = new ArrayList<>();
        State(Path workspace, String toolCallId) {
            this.workspace = workspace;
            this.toolCallId = toolCallId == null ? "result" : toolCallId;
        }
    }
}
