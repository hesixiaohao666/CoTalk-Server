package ai.pro;

/**
 * DeepSeek Search API 的单条搜索结果。
 */
public final class AiProSearchResult {

    public final String title;
    public final String url;
    public final String snippet;
    public final String summary;
    public final String date;
    public final String siteName;

    public AiProSearchResult(String title,
                             String url,
                             String snippet,
                             String summary,
                             String date,
                             String siteName) {
        this.title = title == null ? "" : title.trim();
        this.url = url == null ? "" : url.trim();
        this.snippet = snippet == null ? "" : snippet.trim();
        this.summary = summary == null ? "" : summary.trim();
        this.date = date == null ? "" : date.trim();
        this.siteName = siteName == null ? "" : siteName.trim();
    }

    public String content() {
        return summary.isEmpty() ? snippet : summary;
    }
}
