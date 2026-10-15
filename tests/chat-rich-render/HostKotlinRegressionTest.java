package tests.chat_rich_render;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class HostKotlinRegressionTest {

    private static Method findMethod(Class<?> clazz, String prefix) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().startsWith(prefix)) {
                m.setAccessible(true);
                return m;
            }
        }
        throw new RuntimeException("Method starting with " + prefix + " not found on " + clazz.getName());
    }

    private static Object getInstance(Class<?> clazz) throws Exception {
        Field f = clazz.getDeclaredField("INSTANCE");
        f.setAccessible(true);
        return f.get(null);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Testing Compiled RichResponseView from delta/classes ===");

        Class<?> richClass = Class.forName("dev.wind.agent.RichResponseView");
        Object richInstance = getInstance(richClass);
        Method needsMethod = findMethod(richClass, "needsRichRendering");
        System.out.println("Found RichResponseView method: " + needsMethod.getName());

        // Case 1: Single * or _ italic
        boolean richItalicStar = (Boolean) needsMethod.invoke(richInstance, "这是 *斜体* 测试");
        boolean richItalicUnderscore = (Boolean) needsMethod.invoke(richInstance, "这是 _斜体_ 测试");
        if (!richItalicStar || !richItalicUnderscore) {
            throw new AssertionError("Failed: * or _ italic must trigger rich rendering (star=" + richItalicStar + ", under=" + richItalicUnderscore + ")");
        }
        System.out.println("[PASS] RichResponseView triggers on * and _ italic");

        // Case 2: Link [text](url)
        boolean richLink = (Boolean) needsMethod.invoke(richInstance, "访问 [官网](https://example.org) 获取详情");
        if (!richLink) {
            throw new AssertionError("Failed: Markdown link must trigger rich rendering");
        }
        System.out.println("[PASS] RichResponseView triggers on markdown link");

        // Case 3: Table, blockquote, HR, indented code
        boolean richTable = (Boolean) needsMethod.invoke(richInstance, "| a | b |\n|---|---|\n| 1 | 2 |");
        boolean richQuote = (Boolean) needsMethod.invoke(richInstance, "> 引用段落");
        boolean richHr = (Boolean) needsMethod.invoke(richInstance, "前言\n\n---\n\n后语");
        boolean richIndent = (Boolean) needsMethod.invoke(richInstance, "前言\n\n    val x = 1\n    println(x)");
        if (!richTable || !richQuote || !richHr || !richIndent) {
            throw new AssertionError("Failed: Table, Quote, HR, or Indented code did not trigger rich rendering (table=" + richTable + ", quote=" + richQuote + ", hr=" + richHr + ", indent=" + richIndent + ")");
        }
        System.out.println("[PASS] RichResponseView triggers on Table, Blockquote, HR, Indented Code");

        // Case 4: Math delimiters
        boolean richMathDollar = (Boolean) needsMethod.invoke(richInstance, "公式 $E=mc^2$");
        boolean richMathBracket = (Boolean) needsMethod.invoke(richInstance, "公式 \\(a+b\\)");
        if (!richMathDollar || !richMathBracket) {
            throw new AssertionError("Failed: Math delimiters must trigger rich rendering");
        }
        System.out.println("[PASS] RichResponseView triggers on math delimiters");

        // Case 5: Plain text (should return false)
        boolean richPlainText = (Boolean) needsMethod.invoke(richInstance, "你好，这是一段普通的没有任何标记符号的中文纯文本。");
        if (richPlainText) {
            throw new AssertionError("Failed: Plain text should return false from needsRichRendering");
        }
        System.out.println("[PASS] Plain text correctly returns false (falls back to native TextView)");

        Class<?> markdownClass = Class.forName("dev.wind.agent.MarkdownRenderer");
        Object markdownInstance = getInstance(markdownClass);
        Method normalize = findMethod(markdownClass, "normalizeEscapes");
        System.out.println("Markdown bytecode: " + markdownClass.getProtectionDomain().getCodeSource().getLocation());
        for (String formula : new String[]{"$\\nu\\neq\\theta\\times$", "\\(\\nu_1+\\nu_2\\)", "\\[\\nu\\neq\\mu\\]"}) {
            if (!formula.equals(normalize.invoke(markdownInstance, formula)))
                throw new AssertionError("TeX source changed: " + formula);
        }
        if (!"first\nsecond\nthird".equals(normalize.invoke(markdownInstance, "first\\nsecond\\nthird")))
            throw new AssertionError("Legacy leaked newline repair failed");
        String oneEscape = "println(\"hello\\nworld\")";
        if (!oneEscape.equals(normalize.invoke(markdownInstance, oneEscape)))
            throw new AssertionError("Single code escape changed");
        if (!"".equals(normalize.invoke(markdownInstance, (Object) null)) ||
            !"".equals(normalize.invoke(markdownInstance, "")))
            throw new AssertionError("Empty/null handling changed");
        System.out.println("[PASS] Actual Kotlin bytecode preserves TeX and legacy newline behavior");
        System.out.println("=== RENDER_ENTRY_AND_TEX_BYTECODE_TEST_PASS ===");
    }
}
