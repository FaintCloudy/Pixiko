package cn.szu.bot.domain;

/**
 * 一对正/反向提示词。不可变；{@code null} 一律归一成空串，首尾空白去掉（词条内部的空白与标点保持原样）。
 *
 * <p>提示词是用户自己的内容，{@link #toString()} 只报长度、不报正文——日志里不该出现别人的 prompt。
 */
public record PromptPair(String positive, String negative) {
    /** 空的一对：正反向都没有。 */
    public static final PromptPair EMPTY = new PromptPair("", "");

    public PromptPair {
        positive = positive == null ? "" : positive.strip();
        negative = negative == null ? "" : negative.strip();
    }

    public static PromptPair of(String positive, String negative) {
        return new PromptPair(positive, negative);
    }

    /** 正反向都为空。 */
    public boolean isEmpty() {
        return positive.isEmpty() && negative.isEmpty();
    }

    /** 只换正向，反向不动。 */
    public PromptPair withPositive(String value) {
        return new PromptPair(value, negative);
    }

    /** 只换反向，正向不动。 */
    public PromptPair withNegative(String value) {
        return new PromptPair(positive, value);
    }

    @Override
    public String toString() {
        return "PromptPair(正向 " + positive.length() + " 字符, 反向 " + negative.length() + " 字符)";
    }
}
