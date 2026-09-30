package com.fsck.k9.mail

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.fsck.k9.mail.filter.PeekableInputStream
import com.fsck.k9.mail.internet.MessageIdParser
import com.fsck.k9.mail.internet.MimeParameterDecoder
import com.fsck.k9.mail.internet.MimeParameterEncoder
import java.io.ByteArrayInputStream
import org.junit.Test

/** One test per pooled candidate; a failure means the candidate's claim holds on main. */
class StudyCandidatesTest {
    @Test
    fun `M1 a message id with a one-character part between dots parses`() {
        assertThat(MessageIdParser.parse("<a.b.c@domain.example>")).isEqualTo("<a.b.c@domain.example>")
    }

    @Test
    fun `M2 a later parameter section with bad percent-encoding does not throw`() {
        MimeParameterDecoder.decode("attachment; filename*0*=UTF-8''file; filename*1*=%ZZ")
    }

    @Test
    fun `M3 a long name with U+10000 survives encoding`() {
        val name = "𐀀".repeat(20) + ".txt"
        val header = MimeParameterEncoder.encode("attachment", mapOf("filename" to name))
        assertThat(MimeParameterDecoder.decode(header).parameters["filename"]).isEqualTo(name)
    }

    @Test
    fun `M4 a personal name with quotes survives pack and unpack`() {
        val address = Address("joe@example.com", "Joe \"JJ\" Smith", false)
        assertThat(Address.unpack(Address.pack(arrayOf(address)))[0].personal).isEqualTo("Joe \"JJ\" Smith")
    }

    @Test
    fun `M5 read into a buffer after peeking the end of stream returns -1`() {
        val stream = PeekableInputStream(ByteArrayInputStream(ByteArray(0)))
        stream.peek()
        assertThat(stream.read(ByteArray(4))).isEqualTo(-1)
    }
}
