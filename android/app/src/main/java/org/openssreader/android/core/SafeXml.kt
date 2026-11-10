package org.openssreader.android.core

import java.io.ByteArrayInputStream
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.xml.sax.InputSource
import org.xml.sax.SAXException

/** Strict, Android-compatible DOM parsing. Android's DocumentBuilderFactory does not
 * promise support for Xerces' disallow-doctype-decl or FEATURE_SECURE_PROCESSING.
 * Reject DTD/entity declarations before parsing, refuse all external entity access,
 * and apply supported parser hardening without making an unsupported feature fatal.
 */
object SafeXml {
    private val forbidden = Regex("<!\\s*(?:DOCTYPE|ENTITY)\\b", RegexOption.IGNORE_CASE)
    fun document(bytes: ByteArray, maxBytes: Int = 1024 * 1024): Document {
        require(bytes.isNotEmpty() && bytes.size <= maxBytes) { "XML 大小无效" }
        // Latin-1 is lossless for ASCII markup bytes; removing NUL also handles
        // UTF-16 LE/BE markup without decoding any untrusted declared charset.
        val asciiMarkup = bytes.toString(Charsets.ISO_8859_1).replace("\u0000", "")
        require(!forbidden.containsMatchIn(asciiMarkup)) { "XML 不允许 DTD 或实体声明" }
        return parse(InputSource(ByteArrayInputStream(bytes)))
    }
    fun documentText(text: String, maxChars: Int = 524288): Document {
        require(text.isNotEmpty() && text.length <= maxChars) { "XML 大小无效" }
        require(!forbidden.containsMatchIn(text)) { "XML 不允许 DTD 或实体声明" }
        return parse(InputSource(StringReader(text)))
    }
    private fun parse(source: InputSource): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setExpandEntityReferences(false)
            runCatching { isXIncludeAware = false }
            // All optional on Android. A pre-parse DTD ban plus rejecting
            // entity resolver is always enforced, regardless of availability.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw SAXException("XML 外部实体已禁用") }
        builder.setErrorHandler(object : org.xml.sax.helpers.DefaultHandler() {
            override fun fatalError(e: org.xml.sax.SAXParseException) { throw e }
            override fun error(e: org.xml.sax.SAXParseException) { throw e }
        })
        return builder.parse(source)
    }
}
