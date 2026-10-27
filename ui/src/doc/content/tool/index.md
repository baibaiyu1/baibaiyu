# 工具管理

工具管理模块是智能体能力的"执行层扩展中枢"，通过将内部系统能力、外部 API 服务、自定义业务逻辑封装为标准化工具，赋予智能体感知环境、执行操作、处理复杂任务的能力。

## 功能概述

- 支持内置工具和自定义工具两种类型
- 内置工具随系统启动自动注册，自定义工具通过在线编写代码创建
- 支持按分类组织工具
- 工具可标记「需要确认」，与智能体关联的确认钩子配合，执行前等待用户审批
- 可把成功结果直接作为回答，省去模型二次整理

## 核心概念

### 工具类型

| 类型 | 说明 | 可执行操作 |
|------|------|-----------|
| **内置（BUILTIN）** | 系统预置工具，通过 Java 类路径注册 | 查看详情；管理员可修改展示和执行选项、启用/禁用 |
| **自定义（CUSTOM）** | 用户在线编写的工具代码 | 完整 CRUD |

### 工具属性

| 属性 | 说明 |
|------|------|
| 名称 | 工具显示名称 |
| 编号 | 工具唯一标识符（小写字母+下划线，如 `get_weather`） |
| 描述 | 工具功能描述，影响模型对工具的理解和选择 |
| 标签/分类 | 工具所属分类 |
| 是否需要确认 | 与智能体确认钩子配合，调用前等待用户确认 |
| 结果直接返回 | 成功结果直接作为本轮回答，不再请求模型加工 |
| 版本号 | 工具版本标识 |
| 输入参数 Schema | 工具入参的 JSON Schema 定义 |
| 输出格式 | 可选 JSON Schema 对象，保留工具输出格式信息 |
| 代码 | 自定义工具的实现代码 |

### 工具选择策略

智能体可配置工具选择策略，控制模型如何选择调用工具：

- **自动（AUTO）**：由模型自主决定是否调用工具
- **必选（REQUIRED）**：模型必须调用工具
- **指定（SPECIFIC）**：指定调用某个工具
- **无需（NONE）**：不使用工具

## 操作指南

### 联网搜索与网页阅读

系统提供两个可绑定的内置工具：`web_search` 使用博查 Web Search API 搜索公开网页，`web_fetch` 在本地读取网页正文。工具随 Runtime 启动同步到工具管理，只有绑定到智能体且启用后才会参与对话，不会为所有智能体自动打开联网搜索。

1. 请部署管理员先配置博查密钥；配置方法见仓库 `docker/README.md` 的“博查联网搜索”章节。
2. 在智能体设计页的 **工具与能力 → 工具** 关联 `web_search` 和 `web_fetch`，工具选择策略设为 **AUTO**，保存后在预览中验证。
3. 提问时描述地点、灾害类型及时间，例如“查找某地最近一周暴雨灾害的官方通报，列出发布时间和原文链接”。模型可先搜索，再读取相关原文核对细节。

`web_search` 默认返回 5 条结果，上限由部署管理员设置。支持 `oneDay / oneWeek / oneMonth / oneYear`、指定日期 `YYYY-MM-DD` 和日期区间 `YYYY-MM-DD..YYYY-MM-DD`；查询历史事件应使用对应日期或不限时间，避免被“最近一天”过滤掉。可指定网站域名，多个用 `|` 分隔，例如 `mem.gov.cn|cma.gov.cn|mwr.gov.cn`；服务端会再次过滤不符合网站范围的结果。

结果保留标题、原文链接、网站名称、摘要和可获取的发布时间，缺失时间保持未知；发布时间与事件发生时间应分别核对。应急资料优先核对应急管理、气象、水利、地方政府等官方来源。搜索无结果不代表没有灾害或预警，实时预警应核对官方发布渠道。原文链接可在回答中点击打开，工具调用展示和审批操作见 [对话界面](#/chat)。

`web_fetch` 支持公网 HTML 和纯文本网页，去除脚本、导航等常见干扰并返回正文；默认最多返回 12000 字，可调为 500～30000 字，超出会标记截断。它不执行网页 JavaScript，不处理需要登录的页面或 PDF，不调用付费搜索或摘要服务。网页内容是外部资料，不能替代智能体的系统指令。两个内置工具可在计划模式调查阶段使用，配置的人工确认要求仍然生效。

缺少密钥、认证失败、额度不足、限流和网络错误会显示工具失败，不能当成“没有搜索结果”。每次 `web_search` 调用消耗博查额度；系统不自动重试搜索，同一个问题的多次查询会分别计费，费用以博查账户账单为准。

博查返回 HTTP 403 且提示 `You do not have enough money or package quota` 时，表示账户余额或套餐额度不足，工具会显示“博查搜索额度不足”。请在博查开放平台检查 Web Search 服务的可用额度。

### 创建自定义工具

1. 在工具管理页面点击 **添加新工具** 卡片
2. 填写表单：
   - **标签**：选择或新建分类
   - **名称**：工具名称
   - **编号**：工具标识符（自定义工具必填）
   - **描述**：工具功能说明
   - **是否需要确认**：开关
   - **结果直接返回**：成功结果是否直接作为本轮回答
   - **版本号**：版本标识
   - **输入参数**：定义参数 Schema
   - **输出格式**：可选 JSON Schema 对象
   - **代码**：编写工具实现代码
3. 点击确认提交

:::tip
在线工具目前支持同步工具。异步或流式工具请自行在 `com.gmd.faq.server.main.engine.tool.builtins` 包下编写。
:::

在线工具通过 `execute(AgentContext context, Map<String, Object> params)` 接收上下文和按名称组织的参数。参数排列顺序不影响取值。

### 结果直接返回

编辑工具并开启“结果直接返回”，适合已经排好版的业务答复、确定性查询或报表结果。字符串按原文展示，对象序列化为 JSON；需要用户阅读的答复建议返回字符串或 `TextBlock`。

只有本批所有工具都开启直接返回且执行成功时，才会省去后续模型调用；混合批次仍由模型整理。需要确认的工具仍需审批，方案模式的只读限制也继续生效。拒绝、执行异常和中断不会被当成成功回答；业务失败应抛异常或返回 `ToolResultBlock.error(...)`，普通对象中的 `error` 字段本身不代表失败。

图片和文件产物沿用对话中的既有展示与下载入口，操作见 [对话界面](#/chat)。

### 返回图片

自定义工具可返回 AgentScope 的 `ImageBlock`、包含图片的 `ToolResultBlock`，也可以在普通 JSON 结果中提供图片信息，例如：

```json
{
  "text": "图表生成完成",
  "images": [
    { "url": "https://example.com/chart.png", "name": "销售趋势.png" },
    { "filePath": "charts/result.png", "mimeType": "image/png" },
    { "base64": "图片的 Base64 内容", "mimeType": "image/png" }
  ]
}
```

图片来源可以是 HTTP/HTTPS 地址、当前会话工作空间内的文件路径或 Base64。支持 `imageUrl`、`image_url`、`imageBase64` 字段及 `data:image/png;base64,...` 数据地址；不允许读取当前会话以外的文件。系统保存图片副本，在对话中展示，并将真实图片交给支持视觉输入的模型继续分析。

远程图片默认只允许公网地址；企业内网图片服务需由部署管理员配置 `TOOL_IMAGE_ALLOWED_HOSTS`，逗号分隔精确主机名，下载不附带用户登录凭据。类型、容量限制、视觉输入和预览操作统一见 [对话界面](#/chat) 的「工具返回的图片」。

### 其他操作

- **查看详情**：卡片菜单 → 查看，详情弹窗展示以下字段：

| 字段 | 说明 |
|------|------|
| **关联智能体** | 引用此工具的智能体列表 |
| **工具类型** | 内置（BUILTIN）或自定义（CUSTOM） |
| **分类** | 工具所属分类 |
| **名称** | 工具显示名称 |
| **工具编号** | 工具唯一标识符 |
| **类路径** | 内置工具的 Java 类路径 |
| **描述** | 工具功能说明 |
| **是否需要确认** | 是 / 否 |
| **结果直接返回** | 是 / 否 |
| **输出格式** | 已配置的 JSON Schema |
| **语言** | 自定义工具的编程语言 |
| **输入参数** | 参数列表，包含名称、类型、是否必填、默认值 |
| **代码** | 自定义工具的完整实现代码 |

- **编辑工具**：卡片菜单 → 编辑。内置工具由管理员修改名称、分类、描述、确认要求和直接返回选项；代码及输入参数由系统维护
- **启用/禁用**：卡片菜单 → 启用/禁用，禁用的工具不会参与智能体调用；若工具正在被智能体引用，禁用时会弹出二次确认提示
- **删除工具**：卡片菜单 → 删除（仅自定义工具），若工具被智能体引用会弹出二次确认框

### 搜索与筛选

工具管理支持**双维度筛选**：

- **类型筛选**：页面顶部分段控制器切换全部/内置/自定义，筛选为「内置」时新增卡片自动隐藏
- **分类筛选**：右侧下拉选择器选择分类，分类列表从数据库中所有工具的 `category` 字段去重获取
- **名称搜索**：输入关键词后回车或点击搜索图标搜索

:::tip
类型和分类筛选可以同时使用，例如筛选「自定义」类型下「查询」分类的工具。
:::

## 在智能体中使用

在智能体配置的「工具与能力」步骤中：

1. 在 **工具集** 区域，按分类折叠面板浏览工具
2. 勾选需要的工具（Checkbox 多选）
3. 配置 **工具选择策略**

## 工具描述编写技巧

工具描述直接影响模型选择和使用工具的准确性：

- **明确功能**：清楚说明工具能做什么
- **说明入参**：描述每个参数的含义和取值范围
- **给出示例**：在描述中包含调用场景说明
- **标注限制**：说明工具的适用范围和限制条件

### 好的描述示例

```
查询指定城市的当前天气信息，包括温度、湿度、风速和天气状况。
适用于需要了解天气情况的场景，如出行建议、活动规划等。
仅支持中国主要城市查询。
```

### 差的描述示例

```
获取天气
```

## 自定义工具开发

### 按名称读取参数

工具表单中的参数名就是 `params` 的键。表单排序只影响展示，代码通过名称读取参数。

### 示例：数学计算器

```java
import com.gmd.faq.server.main.engine.tool.dynamices.IDynamicAgentTool;
import com.gmd.faq.server.main.engine.agui.AgentContext;
import java.util.Map;

public class MathCalculator implements IDynamicAgentTool {
    @Override
    public Object execute(AgentContext context, Map<String, Object> params) {
        double left = Double.parseDouble(String.valueOf(params.get("num1")));
        double right = Double.parseDouble(String.valueOf(params.get("num2")));
        String operator = String.valueOf(params.get("operator"));
        double result;
        switch (operator) {
            case "+": result = left + right; break;
            case "-": result = left - right; break;
            case "*": result = left * right; break;
            case "/":
                if (right == 0) throw new IllegalArgumentException("除数不能为零");
                result = left / right;
                break;
            default: throw new IllegalArgumentException("不支持的运算符");
        }
        return left + " " + operator + " " + right + " = " + result;
    }
}
```

在输入参数中添加 `num1`、`num2` 和 `operator`，设为必填。开启直接返回后，上例的成功字符串直接成为本轮回答；数字解析或除零异常返回错误状态。

## 内置工具开发指南

内置工具通过实现 `IAgentTool` 接口并配合注解完成定义，支持 Spring 容器管理，可享受依赖注入等能力。

### 核心接口：IAgentTool

```java
import com.gmd.faq.server.main.common.util.JsonUtils;
import com.gmd.faq.server.main.common.wrapper.ToolInfoWrapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.lang.reflect.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 代理工具接口
 *
 **/
public interface IAgentTool extends SmartInitializingSingleton {

    default ToolInfoWrapper parseToolInfo() {
        try {
            // 遍历类中的所有方法
            for (Method method : this.getClass().getDeclaredMethods()) {
                // 检查方法是否有 @Tool 注解
                if (method.isAnnotationPresent(Tool.class)) {
                    ToolInfoWrapper toolInfo = new ToolInfoWrapper();
                    toolInfo.setClassPath(this.getClass().getName());

                    Tool toolAnnotation = method.getAnnotation(Tool.class);
                    if (toolAnnotation != null) {
                        toolInfo.setName(toolAnnotation.name());
                        toolInfo.setDescription(toolAnnotation.description());
                    }

                    // 解析方法参数
                    List<ToolInfoWrapper.ParamInfo> params = getParamInfos(method);

                    toolInfo.setParams(params);
                    return toolInfo; // 返回第一个找到的 @Tool 方法
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    private static List<ToolInfoWrapper.ParamInfo> getParamInfos(Method method) {
        List<ToolInfoWrapper.ParamInfo> params = new ArrayList<>();
        Parameter[] parameters = method.getParameters();

        for (Parameter param : parameters) {
            // 检查参数是否有 @ToolParam 注解
            if (param.isAnnotationPresent(ToolParam.class)) {
                ToolParam paramAnnotation = param.getAnnotation(ToolParam.class);
                ToolInfoWrapper.ParamInfo paramInfo = new ToolInfoWrapper.ParamInfo();
                if (paramAnnotation != null) {
                    paramInfo.setName(paramAnnotation.name());
                }
                if (paramAnnotation != null) {
                    paramInfo.setDescription(paramAnnotation.description());
                }
                if (paramAnnotation != null) {
                    paramInfo.setRequired(paramAnnotation.required());
                } else {
                    paramInfo.setRequired(false);
                }
                paramInfo.setType(extractParameterType(param));
                params.add(paramInfo);
            }
        }
        return params;
    }

    private static String extractParameterType(Parameter parameter) {
        Class<?> type = parameter.getType();
        Type genericType = parameter.getParameterizedType();

        // 处理基本类型和包装类
        if (type == int.class || type == Integer.class) {
            return "integer";
        } else if (type == long.class || type == Long.class
                || type == float.class || type == Float.class
                || type == double.class || type == Double.class
                || type == byte.class || type == Byte.class
                || type == short.class || type == Short.class) {
            return "number";
        } else if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }

        // 处理数组类型
        if (type.isArray()) {
            return "array";
        }

        // 处理集合类型（List、Set等）
        if (Collection.class.isAssignableFrom(type)) {
            return "array";
        }

        // 处理Map类型
        if (Map.class.isAssignableFrom(type)) {
            return "object";
        }

        // 处理泛型数组（如List<String>[]）
        if (genericType instanceof GenericArrayType) {
            return "array";
        }

        // 处理参数化类型（泛型）
        if (genericType instanceof ParameterizedType pType) {
            Class<?> rawType = (Class<?>) pType.getRawType();

            if (Collection.class.isAssignableFrom(rawType)) {
                return "array";
            } else if (Map.class.isAssignableFrom(rawType)) {
                return "object";
            }
        }

        // 其他所有类型都返回 string
        return "string";
    }

    /**
     * 将对象转换为json字符串
     */
    default String toJsonString(Object obj) {
        return JsonUtils.toJsonStr(obj);
    }

    default void afterSingletonsInstantiated() {
        ToolsRegister.register(this.getClass().getName(), this);
    }
}
```

### 注解说明

| 注解 | 作用 | 关键属性 |
|------|------|---------|
| `@Tool` | 标记工具方法 | `name`：工具名称<br>`description`：工具描述 |
| `@ToolParam` | 描述方法参数 | `name`：参数名称<br>`description`：参数描述<br>`required`：是否必填（默认 false） |

### 编写步骤

1. **创建工具类**，实现 `IAgentTool` 接口
2. **添加 `@Component` 注解**，交由 Spring 管理
3. **定义工具方法**，使用 `@Tool` 注解标注
4. **定义方法参数**，使用 `@ToolParam` 注解描述参数
5. **返回结果**，建议使用 `R.data()` 包装

### 完整示例：获取当前时间工具

```java
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.engine.tool.IAgentTool;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;

/**
 * 获取当前时间工具
 *
 **/
@Component
public class GetCurrentTimeTool implements IAgentTool {

    @Tool(name = "get_current_datetime", description = "获取当前的日期时间")
    public Object getCurrentDateTime(
            @ToolParam(
                    name = "format",
                    description = "日期时间格式，默认值 yyyy-MM-dd HH:mm:ss",
                    required = false)
            String format) {

        if (format == null || format.isEmpty()) {
            format = "yyyy-MM-dd HH:mm:ss";
        }

        return R.data(new SimpleDateFormat(format).format(System.currentTimeMillis()));
    }
}
```

### 更多资源

更多内置工具开发相关内容，请参考：[https://java.agentscope.io/zh/task/tool.html](https://java.agentscope.io/zh/task/tool.html)

## 权限说明

| 操作 | 权限条件 |
| --- | --- |
| 查看列表与详情 | 工具列表权限，且资源在可见范围内 |
| 创建工具 | 工具新增权限 |
| 编辑、启用/禁用、删除 | 对应操作权限，且资源可管理 |

新建工具默认私有；公开和账号/部门授权只改变查看或使用范围，不授予编辑权。统一规则见 [常见问题](#/qa) 的「能看到资源，为什么不能修改或运行」。

## 常见问题

### 内置工具和自定义工具有什么区别？

- **内置工具**：随系统启动自动注册，通过 Java 类实现；管理员可编辑展示及执行选项、启用/禁用，不能在线修改代码或删除
- **自定义工具**：用户通过在线编写代码创建，支持完整 CRUD
- 筛选为「内置」类型时，新增卡片自动隐藏

### 自定义工具如何读取参数？

使用 `params.get("参数名")` 读取参数，名称与表单保持一致。运行身份和当前会话信息来自服务端 `AgentContext`，不要信任工具输入中的账号或租户字段。

### 「是否需要确认」功能怎么不生效？

- 工具需开启「需要确认」，智能体需关联并启用内置钩子 `IConfirmationHook`
- 保存配置后重新进入对话，确认模型实际调用了该工具
- 工具审批不要求开启记忆或显示工具过程；子智能体不执行需要人工审批的工具，此类操作由主智能体发起
- 允许、拒绝和暂停恢复的操作见 [对话界面](#/chat)

### 工具描述为什么很重要？

- 工具描述直接影响大模型对工具的理解和选择准确性
- 好的描述应包含：功能说明、参数含义、调用场景、适用范围和限制条件
- 差的描述（如「获取天气」）会导致模型无法准确判断何时调用工具
- 参考文档中的「工具描述编写技巧」章节获取详细指导

### 如何删除被智能体引用的工具？

- 系统会弹出二次确认框，显示引用的智能体名称
- 可以直接确认删除，但引用方的工具集将缺失该工具
- 建议先在相关智能体配置中解除工具关联，再执行删除

### 自定义工具支持异步吗？

- 在线工具目前仅支持同步模式
- 异步或流式工具请在 `com.gmd.faq.server.main.engine.tool.builtins` 包下编写 Java 代码
- 编写完成后重启服务即可注册为内置工具

### 输入参数的 JSON Schema 怎么写？

- 每个参数需要定义：名称（name）、类型（type）、描述（description）、是否必填（required）
- 支持的参数类型：string、integer、number、boolean、array、object
- 参数类型会根据 Java 方法签名自动推导，也支持手动指定

资源支持个人、部门和平台归属。空间切换、共享范围、维护资格和资产移交统一见 [常见问题](#/qa)。
