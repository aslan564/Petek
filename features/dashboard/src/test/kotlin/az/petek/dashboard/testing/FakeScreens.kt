package az.petek.dashboard.testing

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.random.Random

/**
 * Draws plausible "screenshots" of a KadroHR-like page for the demo and the UI test, so the dashboard's thumbnails
 * look like the real thing without a browser. Deterministic for the same arguments.
 */
object FakeScreens {
    enum class Page(
        val title: String,
    ) {
        LOGIN("Daxil ol"),
        JOIN("Şirkətə qoşul"),
        ANNOUNCEMENTS("Elanlar"),
        NEW_ANNOUNCEMENT("Yeni elan"),
        TASKS("Tapşırıqlar"),
        NOTIFICATIONS("Bildirişlər"),
    }

    private const val WIDTH = 640
    private const val HEIGHT = 400
    private val NAVY = Color(0x1E3A8A)
    private val INK = Color(0x0F172A)
    private val MUTED = Color(0x94A3B8)
    private val LINE = Color(0xE2E8F0)
    private val BAR = Color(0xE5E9F0)
    private val BLUE = Color(0x2563EB)
    private val GREEN = Color(0x16A34A)
    private val AMBER = Color(0xF59E0B)
    private val RED = Color(0xDC2626)

    init {
        System.setProperty("java.awt.headless", "true")
    }

    fun png(
        page: Page,
        user: String,
        variant: Int = 0,
        toast: String? = null,
        error: String? = null,
    ): ByteArray {
        val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val random = Random(page.ordinal * 7919 + variant * 31 + user.hashCode())
            g.color = Color(0xF4F6FA)
            g.fillRect(0, 0, WIDTH, HEIGHT)
            if (page == Page.LOGIN || page == Page.JOIN) {
                authPage(g, page, random)
            } else {
                chrome(g, user, page)
                when (page) {
                    Page.ANNOUNCEMENTS -> announcements(g, random)
                    Page.NEW_ANNOUNCEMENT -> form(g, random)
                    Page.TASKS -> tasks(g, random)
                    else -> notifications(g, random)
                }
            }
            toast?.let { toast(g, it) }
            error?.let { errorBanner(g, it) }
        } finally {
            g.dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun chrome(
        g: Graphics2D,
        user: String,
        page: Page,
    ) {
        g.color = NAVY
        g.fillRect(0, 0, WIDTH, 44)
        g.color = Color.WHITE
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 17)
        g.drawString("KadroHR", 18, 28)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        var x = 120
        for (item in listOf(Page.ANNOUNCEMENTS, Page.TASKS, Page.NOTIFICATIONS)) {
            g.color = if (item == page) Color.WHITE else Color(0xBFDBFE)
            g.drawString(item.title, x, 27)
            if (item == page) g.fillRect(x, 38, g.fontMetrics.stringWidth(item.title), 3)
            x += g.fontMetrics.stringWidth(item.title) + 22
        }
        g.color = Color(0x3B5BDB)
        g.fillOval(WIDTH - 36, 10, 24, 24)
        g.color = Color.WHITE
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 11)
        g.drawString(user.take(1), WIDTH - 28, 26)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        val width = g.fontMetrics.stringWidth(user)
        g.drawString(user, WIDTH - 44 - width, 27)
        g.color = INK
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 20)
        g.drawString(page.title, 24, 80)
    }

    private fun announcements(
        g: Graphics2D,
        random: Random,
    ) {
        button(g, WIDTH - 130, 60, 106, "Elan yarat", BLUE)
        repeat(4) { i ->
            val y = 100 + i * 72
            card(g, 24, y, WIDTH - 48, 62)
            g.color = if (i == 0) AMBER else BAR
            g.fillRoundRect(40, y + 14, 8, 34, 4, 4)
            g.color = INK
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 13)
            g.drawString(
                listOf("Sabah 10:00 ümumi iclas", "Korporativ tədbir", "Məzuniyyət qaydaları", "Ofis köçü")[(i + random.nextInt(4)) % 4],
                60,
                y + 26,
            )
            bar(g, 60, y + 38, 180 + random.nextInt(220))
            g.color = MUTED
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
            g.drawString("${random.nextInt(1, 28)} sen", WIDTH - 90, y + 26)
        }
    }

    private fun form(
        g: Graphics2D,
        random: Random,
    ) {
        card(g, 24, 96, WIDTH - 48, 280)
        label(g, "Başlıq", 44, 124)
        input(g, 44, 132, WIDTH - 88, 30, "Sabah 10:00 ümumi iclas")
        label(g, "Mətn", 44, 186)
        input(g, 44, 194, WIDTH - 88, 86, null)
        repeat(3) { bar(g, 56, 214 + it * 18, 200 + random.nextInt(260)) }
        label(g, "Şöbə", 44, 304)
        input(g, 44, 312, 200, 30, "Hamısı")
        button(g, WIDTH - 150, 312, 106, "Dərc et", GREEN)
    }

    private fun tasks(
        g: Graphics2D,
        random: Random,
    ) {
        card(g, 24, 96, WIDTH - 48, 280)
        val statuses = listOf("Açıq" to BLUE, "İcrada" to AMBER, "Bağlı" to GREEN)
        repeat(6) { i ->
            val y = 128 + i * 40
            g.color = LINE
            g.drawLine(40, y + 14, WIDTH - 40, y + 14)
            g.color = INK
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
            g.drawString("#${120 + i * 7 + random.nextInt(5)}", 44, y)
            bar(g, 100, y - 8, 160 + random.nextInt(160))
            val (text, color) = statuses[random.nextInt(statuses.size)]
            chip(g, WIDTH - 130, y - 13, text, color)
        }
    }

    private fun notifications(
        g: Graphics2D,
        random: Random,
    ) {
        repeat(5) { i ->
            val y = 100 + i * 56
            card(g, 24, y, WIDTH - 48, 48)
            g.color = if (i == 0) BLUE else BAR
            g.fillOval(40, y + 17, 12, 12)
            g.color = INK
            g.font = Font(Font.SANS_SERIF, if (i == 0) Font.BOLD else Font.PLAIN, 12)
            g.drawString(if (i == 0) "Yeni elan: Sabah 10:00 ümumi iclas" else "Tapşırıq yeniləndi", 64, y + 22)
            bar(g, 64, y + 30, 120 + random.nextInt(200))
        }
    }

    private fun authPage(
        g: Graphics2D,
        page: Page,
        random: Random,
    ) {
        g.color = NAVY
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 22)
        g.drawString("KadroHR", WIDTH / 2 - 48, 62)
        card(g, WIDTH / 2 - 150, 84, 300, 270)
        g.color = INK
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 17)
        g.drawString(page.title, WIDTH / 2 - 126, 120)
        label(g, "E-poçt", WIDTH / 2 - 126, 148)
        input(g, WIDTH / 2 - 126, 156, 252, 30, "user${random.nextInt(99)}@test.kadrohr.com")
        label(g, if (page == Page.JOIN) "Şirkət kodu" else "Parol", WIDTH / 2 - 126, 206)
        input(g, WIDTH / 2 - 126, 214, 252, 30, if (page == Page.JOIN) "KDR-4821" else "••••••••")
        button(g, WIDTH / 2 - 126, 268, 252, if (page == Page.JOIN) "Qoşul" else "Daxil ol", BLUE)
    }

    private fun toast(
        g: Graphics2D,
        text: String,
    ) {
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 12)
        val width = g.fontMetrics.stringWidth(text) + 40
        g.color = Color(0x0F172A)
        g.fillRoundRect(WIDTH - width - 16, HEIGHT - 58, width, 40, 12, 12)
        g.color = GREEN
        g.fillOval(WIDTH - width - 2, HEIGHT - 43, 10, 10)
        g.color = Color.WHITE
        g.drawString(text, WIDTH - width + 16, HEIGHT - 33)
    }

    private fun errorBanner(
        g: Graphics2D,
        text: String,
    ) {
        g.color = Color(0xFEE2E2)
        g.fillRoundRect(24, 50, WIDTH - 48, 32, 10, 10)
        g.color = RED
        g.stroke = BasicStroke(1.2f)
        g.drawRoundRect(24, 50, WIDTH - 48, 32, 10, 10)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 12)
        g.drawString("⚠ $text", 38, 71)
    }

    private fun card(
        g: Graphics2D,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    ) {
        g.color = Color.WHITE
        g.fillRoundRect(x, y, w, h, 12, 12)
        g.color = LINE
        g.stroke = BasicStroke(1f)
        g.drawRoundRect(x, y, w, h, 12, 12)
    }

    private fun bar(
        g: Graphics2D,
        x: Int,
        y: Int,
        w: Int,
    ) {
        g.color = BAR
        g.fillRoundRect(x, y, w, 8, 8, 8)
    }

    private fun label(
        g: Graphics2D,
        text: String,
        x: Int,
        y: Int,
    ) {
        g.color = Color(0x475569)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 11)
        g.drawString(text, x, y)
    }

    private fun input(
        g: Graphics2D,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        value: String?,
    ) {
        g.color = Color.WHITE
        g.fillRoundRect(x, y, w, h, 8, 8)
        g.color = Color(0xCBD5E1)
        g.drawRoundRect(x, y, w, h, 8, 8)
        if (value != null) {
            g.color = INK
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
            g.drawString(value, x + 10, y + 20)
        }
    }

    private fun button(
        g: Graphics2D,
        x: Int,
        y: Int,
        w: Int,
        text: String,
        color: Color,
    ) {
        g.color = color
        g.fillRoundRect(x, y, w, 30, 10, 10)
        g.color = Color.WHITE
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 12)
        g.drawString(text, x + (w - g.fontMetrics.stringWidth(text)) / 2, y + 20)
    }

    private fun chip(
        g: Graphics2D,
        x: Int,
        y: Int,
        text: String,
        color: Color,
    ) {
        g.color = Color(color.red, color.green, color.blue, 40)
        g.fillRoundRect(x, y, 80, 20, 10, 10)
        g.color = color
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 11)
        g.drawString(text, x + (80 - g.fontMetrics.stringWidth(text)) / 2, y + 14)
    }
}
