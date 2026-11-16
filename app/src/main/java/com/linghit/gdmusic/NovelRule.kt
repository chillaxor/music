package com.linghit.gdmusic

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.Elements
import java.net.URI

/**
 * Legado / so-novel 静态书源规则求值。
 *
 * 支持：
 *  - CSS 选择器（含 jsoup 的 :contains / :nth-of-type）
 *  - Legado 简写 class.x / tag.x / id.x / text.x
 *  - 下标 .0 / .-1、多下标 :3:4、排除 !0
 *  - @text / @html / @textNodes / @href / @src / @content
 *  - ##正则删除、##正则##替换###、&& 拼接
 * 不支持（在 [NovelSourceParser.supported] 已跳过）：@js: / <js> / JSONPath。
 */
object NovelRule {

    private data class Transform(val pattern: String, val replacement: String, val global: Boolean)
    private data class Parts(val expr: String, val transforms: List<Transform>)

    fun resolve(url: String?, base: String?): String {
        val u = url?.trim().orEmpty()
        if (u.isEmpty()) return ""
        if (u.startsWith("http://") || u.startsWith("https://") || u.startsWith("data:")) return u
        if (u.startsWith("//")) return "https:$u"
        val b = base?.trim().orEmpty()
        if (b.isEmpty()) return u
        return runCatching { URI(b).resolve(u).toString() }.getOrDefault(u)
    }

    /** 在文档/元素上求值，返回字符串 */
    fun eval(root: Element, rule: String?): String {
        if (rule.isNullOrBlank()) return ""
        val (expr, transforms) = split(rule)
        val raw = if (expr.contains("&&")) {
            expr.split("&&").joinToString("") { evalOne(root, it) }
        } else evalOne(root, expr)
        return applyTransforms(raw, transforms)
    }

    /** 求值出节点列表（用于 bookList / chapterList） */
    fun evalList(root: Element, rule: String?): List<Element> {
        if (rule.isNullOrBlank()) return emptyList()
        val (expr, _) = split(rule)
        var nodes: List<Element> = listOf(root)
        for (step in expr.split("@")) {
            val s = step.trim()
            if (s.isEmpty() || isExtraction(s)) continue
            nodes = nodes.flatMap { select(it, s) }
            if (nodes.isEmpty()) break
        }
        return nodes
    }

    private fun evalOne(root: Element, exprRaw: String): String {
        val expr = exprRaw.trim()
        if (expr.isEmpty()) return ""
        val steps = expr.split("@").map { it.trim() }.filter { it.isNotEmpty() }
        var nodes: List<Element> = listOf(root)
        var extracted: String? = null
        for (step in steps) {
            if (isExtraction(step)) {
                extracted = extract(nodes, step)
            } else {
                nodes = nodes.flatMap { select(it, step) }
            }
        }
        if (extracted != null) return extracted
        return nodes.firstOrNull()?.text().orEmpty()
    }

    private fun isExtraction(step: String): Boolean = when (step.lowercase()) {
        "text", "html", "textnodes", "owntext", "href", "src", "content", "value", "title", "alt" -> true
        else -> false
    }

    private fun extract(nodes: List<Element>, step: String): String {
        if (nodes.isEmpty()) return ""
        val el = nodes.first()
        return when (step.lowercase()) {
            "text" -> el.text()
            "owntext" -> el.ownText()
            "html" -> el.html()
            "textnodes" -> el.childNodes().joinToString("\n") { nodeText(it) }.trim()
            "href" -> el.attr("href")
            "src" -> el.attr("src")
            "content" -> el.attr("content")
            "value" -> el.attr("value")
            "title" -> el.attr("title")
            "alt" -> el.attr("alt")
            else -> el.attr(step)
        }
    }

    private fun nodeText(node: Node): String = when (node) {
        is TextNode -> node.text()
        is Element -> if (node.tagName() in BLOCK_TAGS) "\n${node.text()}\n" else node.text()
        else -> ""
    }

    private val BLOCK_TAGS = setOf("br", "p", "div", "li", "h1", "h2", "h3", "h4", "h5", "h6", "tr", "section", "article")

    /** 把一步 Legado/CSS 选择符转成 jsoup 查询结果，带下标/排除语法 */
    private fun select(root: Element, tokenRaw: String): List<Element> {
        val token = tokenRaw.trim()
        val (css, indices, excluded) = parseSelector(token)
        if (css.isBlank()) return emptyList()
        val found: Elements = runCatching { root.select(css) }.getOrDefault(Elements())
        if (indices.isNotEmpty()) {
            return indices.mapNotNull { i ->
                val at = if (i < 0) found.size + i else i
                found.getOrNull(at)
            }
        }
        if (excluded.isNotEmpty()) {
            return found.filterIndexed { i, _ -> i !in excluded }
        }
        return found.toList()
    }

    /** 解析 class.x.2 / tag.a.0 / a.0 / td.-1:-2 / tag.tr!0 / .grid:not(:first-child) */
    private fun parseSelector(token: String): Triple<String, List<Int>, Set<Int>> {
        var t = token
        var excluded = emptySet<Int>()
        val bang = t.indexOf('!')
        if (bang > 0 && t.substring(bang + 1).all { it.isDigit() || it == ':' || it == '-' }) {
            excluded = t.substring(bang + 1).split(':').mapNotNull { it.toIntOrNull() }.toSet()
            t = t.substring(0, bang)
        }
        val m = Regex("""\.[-+]?\d+(?::[-+]?\d+)*$""").find(t)
        var indices = emptyList<Int>()
        if (m != null) {
            indices = m.value.removePrefix(".").split(':').mapNotNull { it.toIntOrNull() }
            t = t.removeRange(m.range)
        }
        val css = when {
            t.startsWith("class.") -> "." + t.removePrefix("class.")
            t.startsWith("tag.") -> t.removePrefix("tag.")
            t.startsWith("id.") -> "#" + t.removePrefix("id.")
            t.startsWith("text.") -> ":contains(" + t.removePrefix("text.") + ")"
            else -> t
        }
        return Triple(css, indices, excluded)
    }

    private fun split(rule: String): Parts {
        val idx = rule.indexOf("##")
        if (idx < 0) return Parts(rule, emptyList())
        val expr = rule.substring(0, idx)
        val tokens = rule.substring(idx + 2).split("##").toMutableList()
        val out = mutableListOf<Transform>()
        var i = 0
        while (i < tokens.size) {
            val pattern = tokens[i].trim()
            if (i + 1 < tokens.size) {
                val replRaw = tokens[i + 1]
                val global = replRaw.endsWith("#")
                val replacement = replRaw.removeSuffix("#")
                out.add(Transform(pattern, replacement, global))
                i += 2
            } else {
                out.add(Transform(pattern, "", true))
                i++
            }
        }
        return Parts(expr, out)
    }

    private fun applyTransforms(value: String, transforms: List<Transform>): String {
        var out = value
        for (t in transforms) {
            runCatching {
                val re = Regex(t.pattern, RegexOption.DOT_MATCHES_ALL)
                out = if (t.global) re.replace(out, t.replacement) else re.replaceFirst(out, t.replacement)
            }
        }
        return out.trim()
    }

    /** HTML 片段转可阅读纯文本，块级标签换行 */
    fun htmlToText(html: String): String {
        if (html.isBlank()) return ""
        val doc = runCatching { org.jsoup.Jsoup.parseBodyFragment(html) }.getOrNull() ?: return html
        doc.select("script,style,iframe,nav,footer,header").remove()
        val body = doc.body() ?: return ""
        val sb = StringBuilder()
        appendText(body, sb)
        return sb.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun appendText(el: Element, sb: StringBuilder) {
        for (node in el.childNodes()) {
            when (node) {
                is TextNode -> sb.append(node.text())
                is Element -> {
                    if (node.tagName() == "br") { sb.append('\n'); continue }
                    if (node.tagName() in BLOCK_TAGS && sb.isNotEmpty() && !sb.endsWith('\n')) sb.append('\n')
                    appendText(node, sb)
                    if (node.tagName() in BLOCK_TAGS && !sb.endsWith('\n')) sb.append('\n')
                }
            }
        }
    }
}
