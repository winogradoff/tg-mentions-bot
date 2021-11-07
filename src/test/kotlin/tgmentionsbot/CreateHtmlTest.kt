package tgmentionsbot

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

internal class CreateHtmlTest {

    @Test
    fun text() {
        val s = "123 qwerty привет!"
        assertThat(createHTML { text(s) }).isEqualTo(s)
    }

    @Test
    fun escape() {
        val s = "<b>bold text</b>"
        assertThat(createHTML { escape(s) }).isEqualTo("&lt;b&gt;bold text&lt;/b&gt;")
    }

    @Test
    fun newline() {
        assertThat(createHTML { newline() }).isEqualTo("\n")
    }

    @Test
    fun bold() {
        assertThat(createHTML { bold("qwerty") }).isEqualTo("<b>qwerty</b>")
    }

    @Test
    fun `bold - nested`() {
        assertThat(createHTML { bold { text("qwerty") } }).isEqualTo("<b>qwerty</b>")
    }

    @Test
    fun pre() {
        assertThat(createHTML { pre("qwerty") }).isEqualTo("<pre>qwerty</pre>")
    }

    @Test
    fun `pre - nested`() {
        assertThat(createHTML { pre { text("qwerty") } }).isEqualTo("<pre>qwerty</pre>")
    }

    @Test
    fun link() {
        assertThat(createHTML { link(url = "https://google.com") { text("Google it!") } })
            .isEqualTo("<a href='https://google.com'>Google it!</a>")
    }
}
