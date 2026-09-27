package cn.szu.bot;
import cn.szu.bot.chat.WebSearch;

public final class WebSearchTest {
    public static void main(String[] args) throws Exception {
        String encoded=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("https://example.com/news".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String markdown="""
            navigation junk
            ## [**Example** News](https://www.bing.com/ck/a?x=1&u=a1%s&ntb=1)

            A useful current summary with **formatting**.
            ## [Second source](https://example.org/report)

            Another summary.
            """.formatted(encoded);
        String result=WebSearch.parse(markdown);
        assert result.contains("[1] Example News") && result.contains("https://example.com/news");
        assert result.contains("[2] Second source") && result.contains("Another summary");
        assert !result.contains("navigation junk");
        System.out.println("WebSearchTest PASS: bounded result extraction, redirect decoding, snippets.");
    }
}
