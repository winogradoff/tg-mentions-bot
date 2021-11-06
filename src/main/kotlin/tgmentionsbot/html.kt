package tgmentionsbot

import org.apache.commons.text.StringEscapeUtils

fun createHTML(block: HtmlContext.() -> Unit): String {
    val context = HtmlContext()
    context.block()
    return context.toString()
}

class HtmlContext {
    private val tags = mutableListOf<String>()

    override fun toString(): String = tags.joinToString(separator = "")

    fun text(s: String) {
        tags += s
    }

    fun escape(s: String) {
        tags += StringEscapeUtils.escapeHtml4(s)
    }

    fun newline() {
        tags += "\n"
    }

    fun bold(block: HtmlContext.() -> Unit) {
        tags += surround(prefix = "<b>", postfix = "</b>") { block() }
    }

    fun pre(block: HtmlContext.() -> Unit) {
        tags += surround(prefix = "<pre>", postfix = "</pre>") { block() }
    }

    fun link(url: String, block: HtmlContext.() -> Unit) {
        tags += surround(prefix = "<a href='$url'>", postfix = "</a>") { block() }
    }

    private fun surround(prefix: String, postfix: String, block: HtmlContext.() -> Unit): String {
        val context = HtmlContext()
        with(context) {
            text(prefix)
            block()
            text(postfix)
        }
        return context.toString()
    }
}
