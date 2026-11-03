package org.wwz.ai.domain.agent.runtime.tool.common.openui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * OpenUI 静态产物的 classpath 读取器。
 *
 * <p>两个文件由 {@code ui/scripts/openui-generate.mjs} 从 {@code library.ts} 生成并提交进仓库，
 * 运行期只读，不依赖 Node。首次访问时读入并缓存，之后是纯内存读取。</p>
 */
public final class OpenUiResources {

    /** 常驻提示词：注入主 agent 的 system prompt。 */
    public static final String RESIDENT_PROMPT = "openui/prompt-resident.txt";

    /** 扩展组件目录：get_ui_components 的返回值。 */
    public static final String COMPONENTS_CATALOG = "openui/components-extended.txt";

    /** Autofix 用的 cloud 形态组件库声明（`]]>openui:config` 块），只发给 Thesys，不进模型上下文。 */
    public static final String AUTOFIX_CONFIG = "openui/openui-cloud-config.txt";

    private static final LazyResource RESIDENT_PROMPT_RESOURCE = new LazyResource(RESIDENT_PROMPT);
    private static final LazyResource COMPONENTS_CATALOG_RESOURCE = new LazyResource(COMPONENTS_CATALOG);
    private static final LazyResource AUTOFIX_CONFIG_RESOURCE = new LazyResource(AUTOFIX_CONFIG);

    private OpenUiResources() {
    }

    public static String residentPrompt() {
        return RESIDENT_PROMPT_RESOURCE.get();
    }

    public static String componentsCatalog() {
        return COMPONENTS_CATALOG_RESOURCE.get();
    }

    public static String autofixConfigBlock() {
        return AUTOFIX_CONFIG_RESOURCE.get();
    }

    /** 单一实现的 volatile 双检懒加载，避免三份拷贝各自演化。 */
    private static final class LazyResource {
        private final String path;
        private volatile String value;

        private LazyResource(String path) {
            this.path = path;
        }

        private String get() {
            String cached = value;
            if (cached == null) {
                synchronized (this) {
                    cached = value;
                    if (cached == null) {
                        cached = load(path);
                        value = cached;
                    }
                }
            }
            return cached;
        }
    }

    private static String load(String path) {
        ClassLoader loader = OpenUiResources.class.getClassLoader();
        try (InputStream stream = loader.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("缺少 OpenUI 静态产物: " + path
                        + "（应在 Cute-agent-domain/src/main/resources 下，"
                        + "由 ui 目录 `pnpm openui:build` 生成）");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 OpenUI 静态产物失败: " + path, e);
        }
    }
}
