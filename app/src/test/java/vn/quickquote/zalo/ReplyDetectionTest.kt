package vn.quickquote.zalo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyDetectionTest {
    private val target = "kiểm tra giúp tôi"

    private fun labels(vararg nodes: ReplyCandidate) =
        ReplyDetection.barLabels(nodes.toList(), inputTop = 800, maxDistance = 140)

    @Test
    fun unchangedTargetNearInputDoesNotConfirmReply() {
        val screen = labels(ReplyCandidate(target, 730, 780, inMessageList = true))
        assertFalse(ReplyDetection.hasNewReply(screen, screen, target))
    }

    @Test
    fun newMatchingMessageInChatDoesNotConfirmReply() {
        val after = labels(ReplyCandidate(target, 730, 780, inMessageList = true))
        assertFalse(ReplyDetection.hasNewReply(emptySet(), after, target))
    }

    @Test
    fun messageSayingReplyDoesNotConfirmReply() {
        val after = labels(ReplyCandidate("trả lời giúp tôi", 700, 750, inMessageList = true))
        assertFalse(ReplyDetection.hasNewReply(emptySet(), after, null))
    }

    @Test
    fun newQuotePreviewOutsideMessageListConfirmsReply() {
        val after = labels(
            ReplyCandidate(target, 670, 710, inMessageList = true),
            ReplyCandidate(target, 740, 790, inMessageList = false)
        )
        assertTrue(ReplyDetection.hasNewReply(emptySet(), after, target))
    }

    @Test
    fun existingQuoteDoesNotConfirmANewReply() {
        val screen = labels(ReplyCandidate(target, 740, 790, inMessageList = false))
        assertFalse(ReplyDetection.hasNewReply(screen, screen, target))
    }

    @Test
    fun newReplyHeaderSupportsTargetsWithoutText() {
        val after = labels(ReplyCandidate("đang trả lời ảnh", 740, 790, inMessageList = false))
        assertTrue(ReplyDetection.hasNewReply(emptySet(), after, null))
    }

    @Test
    fun inputFocusOrLayoutChangeWithoutReplyLabelsDoesNotConfirmReply() {
        assertFalse(ReplyDetection.hasNewReply(emptySet(), emptySet(), target))
    }

    @Test
    fun matchingLabelsOutsideReplyAreaAreIgnored() {
        val after = labels(
            ReplyCandidate(target, 100, 150, inMessageList = false),
            ReplyCandidate(target, 790, 820, inMessageList = false),
            ReplyCandidate(target, 810, 850, inMessageList = false)
        )
        assertFalse(ReplyDetection.hasNewReply(emptySet(), after, target))
    }
    // Nhãn hàng tin file trên Zalo có giờ ở đầu, thanh trả lời thì không
    private val fileRow = "16:53 hôm nay  [file] zcar click.apk apk"

    @Test
    fun fileMessageWithTimePrefixConfirmsReplyWhenBarShowsFileName() {
        val after = labels(
            ReplyCandidate(fileRow, 565, 700, inMessageList = true),
            ReplyCandidate("zcar click.apk", 740, 790, inMessageList = false)
        )
        assertTrue(ReplyDetection.hasNewReply(emptySet(), after, fileRow))
    }

    @Test
    fun fileMessageConfirmsReplyWhenBarShowsFileTag() {
        val after = labels(ReplyCandidate("[file] zcar click.apk", 740, 790, inMessageList = false))
        assertTrue(ReplyDetection.hasNewReply(emptySet(), after, fileRow))
    }

    @Test
    fun timeOnlyLabelNearInputDoesNotConfirmReply() {
        val after = labels(ReplyCandidate("16:53", 740, 790, inMessageList = false))
        assertFalse(ReplyDetection.hasNewReply(emptySet(), after, fileRow))
    }

    @Test
    fun fileRowInsideMessageListStillDoesNotConfirmReply() {
        val screen = labels(ReplyCandidate(fileRow, 740, 790, inMessageList = true))
        assertFalse(ReplyDetection.hasNewReply(emptySet(), screen, fileRow))
    }

    @Test
    fun probesDropTimeAndDate() {
        assertTrue(ReplyDetection.probes(fileRow) == listOf("[file] zcar click.apk apk"))
    }

    @Test
    fun typingIndicatorWithSenderNameDoesNotConfirmReply() {
        val row = "nguyễn văn a  kiểm tra giúp tôi"
        val after = labels(ReplyCandidate("nguyễn văn a đang soạn tin", 740, 790, inMessageList = false))
        assertFalse(ReplyDetection.hasNewReply(emptySet(), after, row))
    }

    @Test
    fun replyBarDetectedByViewIdConfirmsReply() {
        val after = labels(ReplyCandidate("trả lời [id=layout_reply_bar]", 740, 790, inMessageList = false))
        assertTrue(ReplyDetection.hasNewReply(emptySet(), after, fileRow))
    }
}
