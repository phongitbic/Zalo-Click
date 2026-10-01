package vn.quickquote.zalo

/** Nhãn gần ô nhập cùng vị trí và nguồn của nó trong cây trợ năng. */
internal data class ReplyCandidate(
    val label: String,
    val top: Int,
    val bottom: Int,
    val inMessageList: Boolean
)

internal object ReplyDetection {
    private val replyWords = listOf("trả lời", "đang trả lời", "replying", "reply to")

    /** Nhãn mới xuất hiện gần ô nhập nhưng không phải thanh trả lời (vd: "A đang soạn tin"). */
    private val ignoredWords = listOf("đang soạn", "đang nhập", "is typing", "typing")

    /** Giờ/ngày Zalo gắn vào nhãn hàng tin nhắn nhưng không có trên thanh trả lời. */
    private val timeParts = Regex(
        """\b\d{1,2}:\d{2}\b|hôm nay|hôm qua|\b\d{1,2}/\d{1,2}(/\d{2,4})?\b|\bvừa xong\b"""
    )
    private val segmentSplit = Regex("""\s{2,}|\n""")
    private val spaces = Regex("""\s+""")

    fun barLabels(candidates: List<ReplyCandidate>, inputTop: Int, maxDistance: Int): Set<String> =
        candidates.asSequence()
            .filter {
                !it.inMessageList && it.bottom > it.top &&
                    it.top >= inputTop - maxDistance && it.bottom <= inputTop
            }
            .map { it.label }
            .toSet()

    /**
     * Các đoạn nội dung của tin gốc dùng để dò trên thanh trả lời:
     * bỏ giờ/ngày, tách theo khoảng trắng kép (Zalo nối các phần của hàng tin bằng 2 dấu cách).
     */
    fun probes(targetLabel: String?): List<String> {
        if (targetLabel == null) return emptyList()
        return targetLabel.split(segmentSplit)
            .map { timeParts.replace(it, " ").replace(spaces, " ").trim() }
            .filter { it.length >= 4 }
            .distinct()
    }

    fun hasNewReply(before: Set<String>, after: Set<String>, targetLabel: String?): Boolean {
        val probes = probes(targetLabel)
        return (after - before).any { label ->
            if (ignoredWords.any { label.contains(it) }) return@any false
            replyWords.any { label.startsWith(it) } ||
                probes.any { p ->
                    // Thanh trả lời chứa đầu nội dung tin gốc, hoặc hiện một phần rút gọn của nó
                    label.contains(p.take(15)) || (label.length >= 6 && p.contains(label))
                }
        }
    }
}
