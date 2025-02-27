package wiresegal.wob.coppermind

import com.overzealous.remark.Options
import com.overzealous.remark.Remark
import com.overzealous.remark.convert.InlineStyle
import de.btobastian.javacord.entities.channels.PrivateChannel
import de.btobastian.javacord.entities.message.Message
import de.btobastian.javacord.entities.message.embed.EmbedBuilder
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.wikipedia.Wiki
import wiresegal.wob.arcanum.DESCRIPTION_LIMIT
import wiresegal.wob.arcanum.EMBED_LIMIT
import wiresegal.wob.arcanum.TITLE_LIMIT
import wiresegal.wob.misc.catch
import wiresegal.wob.misc.setupControls
import wiresegal.wob.misc.setupDeletable
import wiresegal.wob.misc.then
import wiresegal.wob.plugin.sendError
import wiresegal.wob.wikiEmbedColor
import wiresegal.wob.wikiIconUrl
import wiresegal.wob.wikiTarget
import java.io.FileNotFoundException

/**
 * @author WireSegal
 * Created at 10:12 PM on 4/7/18.
 */

val wiki = Coppermind()
val wikiMarkup = Remark(Options.github().apply { inlineLinks = true; preserveRelativeLinks }).apply {
    converter.addInlineNode(InlineStyle(), "blockquote")
}

fun Remark.convert(node: List<Node>, base: String): String = convertFragment(node.joinToString(""), base)
fun Remark.convert(node: Node, base: String): String = convertFragment(node.toString(), base)

class Coppermind : Wiki(wikiTarget) {

    fun getSectionHTML(title: String): String {
        return parse(getPageText(title)).replace("API", title.replace("[+_]".toRegex(), " "))
    }

    fun getDocument(title: String): Document {
        return Jsoup.parse(getSectionHTML(title), "https://$wikiTarget")
    }

    fun resolveFragmentRedirect(title: String) = resolveFragmentRedirect(arrayOf(title))[0]

    fun resolveFragmentRedirect(titles: Array<String>): Array<String?> {
        val url = StringBuilder(query)
        if (!isResolvingRedirects)
            url.append("redirects&")
        url.append("titles=")
        val ret = arrayOfNulls<String>(titles.size)
        val temp = constructTitleString(titles)
        for (blah in temp) {
            val line = fetch(url.toString() + blah, "resolveRedirects")
            var j = line.indexOf("<r ")
            while (j > 0) {
                val parsedtitle = parseAttribute(line, "from", j)
                for (i in titles.indices)
                    if (normalize(titles[i]) == parsedtitle) {
                        ret[i] = parseAttribute(line, "to", j)
                        val frag = parseAttribute(line, "tofragment", j)
                        if (frag != null)
                            ret[i] += "#$frag"
                    }
                j = line.indexOf("<r ", ++j)
            }
        }
        return ret
    }

    override fun fetch(url: String, caller: String): String {
        return super.fetch(url.replace("&intoken=[^&]+".toRegex(), ""), caller)
    }
}

fun fetchPreview(searchInfo: String): Pair<List<String>, String> {
    val x = wiki.getDocument(searchInfo)
    x.getElementsByClass("infobox").forEach(Element::remove)
    x.getElementsByClass("toc").forEach(Element::remove)
    x.getElementsByClass("reference").forEach(Element::remove)
    x.getElementsByClass("mw-references-wrap").forEach(Element::remove)
    x.getElementsByClass("thumb").forEach(Element::remove)

    val body = x.body()
    val allNotices = body.children().takeWhile { it.hasClass("notice") }
    allNotices.forEach(Element::remove)

    val sectionHeader = if ('#' in searchInfo)
        x.getElementById(searchInfo.split("#")[1].replace("[+\\s]".toRegex(), "_"))?.parent() ?: body.child(0)
    else
        body.child(0)

    val sectionNodes = mutableListOf<Element>()
    if (!sectionHeader.tagName().startsWith("h"))
        sectionNodes.add(sectionHeader)

    var next = sectionHeader.nextElementSibling()
    while (next != null && !next.tagName().startsWith("h")) {
        sectionNodes.add(next)
        next = next.nextElementSibling()
    }

    val notices = allNotices.filter { it.childNodeSize() > 0 }.map { wikiMarkup.convert(it.child(0), "https://$wikiTarget") }
    var splits = wikiMarkup.convert(sectionNodes, "https://$wikiTarget").split("(?<!\\*\\*”\\*\\*)\n{2,}".toRegex())

    if (splits.none { "http://en.wikipedia.org/wiki/Help:Disambiguation" in it })
        splits = splits.take(2)

    val md = splits.joinToString("\n\n")

    return notices to md
}

fun searchResults(searchInfo: String): Pair<Boolean, List<String>> {
    val rawName = searchInfo.replace("[+\\s]".toRegex(), "_")
    return if (wiki.getPageInfo(rawName)["exists"] as Boolean)
        false to listOf(wiki.resolveFragmentRedirect(rawName) ?: rawName)
    else {
        val search = searchInfo.split("\\s+".toRegex())
        val allArticles = wiki.search(searchInfo)
                .map { it.first() }
        val raw = allArticles.firstOrNull { it.toLowerCase() == rawName.toLowerCase() }
        if (raw != null)
            false to listOf(wiki.resolveFragmentRedirect(raw) ?: raw)
        else {
            val articles = allArticles.take(10)
            val redirected = wiki.resolveRedirects(articles.toTypedArray())
            (articles.size != allArticles.size) to redirected.mapIndexed { idx, it -> it ?: articles[idx] }
                    .toSet()
                    .sortedBy { search.count { term -> term in it } }
        }
    }

}

fun embedFromWiki(titlePrefix: String, name: String, entry: Pair<List<String>, String>): EmbedBuilder {
    val (notices, body) = entry

    val title = (titlePrefix + name.replace("+", " ").replace("#", ": ")).take(TITLE_LIMIT)

    val embed = EmbedBuilder()
            .setColor(wikiEmbedColor)
            .setTitle(title)
            .setUrl("https://$wikiTarget/wiki/" + name.replace("[+\\s]".toRegex(), "_"))
            .setThumbnail(wikiIconUrl)

    val description = mutableListOf<String>()

    notices.mapTo(description) { "**$it**" }
    description.add(body)

    var desc = description.joinToString("\n\n")
    if (desc.length > DESCRIPTION_LIMIT)
        desc = desc.substring(0, "\\.[\"”'’]?\\s".toRegex().findAll(desc)
                .lastOrNull { it.range.start <= DESCRIPTION_LIMIT }?.range?.endInclusive ?: DESCRIPTION_LIMIT)

    embed.setDescription(desc)

    if (embed.toJsonNode().toString().length > EMBED_LIMIT)
        return backupEmbed(titlePrefix, name)

    return embed
}

fun backupEmbed(title: String, name: String): EmbedBuilder {
    return EmbedBuilder().setColor(wikiEmbedColor).setTitle(title + name.replace("+", " ").replace("#", ": "))
            .setUrl("https://$wikiTarget/wiki/" + name.replace("[+\\s]".toRegex(), "_"))
            .setThumbnail(wikiIconUrl).setDescription("An error occurred in loading the wiki preview.")
}

fun harvestFromWiki(terms: List<String>): List<EmbedBuilder> {
    val (large, rawArticles) = searchResults(terms.joinToString("+"))
    val allArticles = rawArticles.map { it to fetchPreview(it) }
    val allEmbeds = mutableListOf<EmbedBuilder>()

    val size = if (large) "... (10)" else allArticles.size.toString()

    for ((idx, article) in allArticles.withIndex()) {
        val (name, body) = article
        val titleText = "Search: \"${terms.joinToString(" ")}\" (${idx+1}/$size) \n"
        allEmbeds.add(embedFromWiki(titleText, name, body))
    }

    return allEmbeds
}

fun searchCoppermind(message: Message, terms: List<String>) {
    var type = AutoCloseable {}

    message.channel.sendMessage("Searching for \"${terms.joinToString(" ")}\"...").then {
        type = message.channel.typeContinuously()
        val allEmbeds = harvestFromWiki(terms)

        type.close()

        try {
            when {
                allEmbeds.isEmpty() -> message.channel.sendMessage("Couldn't find any articles for \"${terms.joinToString(" ")}\".")
                allEmbeds.size == 1 -> {
                    val finalEmbed = allEmbeds.first()
                    finalEmbed.setTitle(finalEmbed.toJsonNode()["title"].asText().replace(".*\n".toRegex(), ""))
                    message.channel.sendMessage(finalEmbed).setupDeletable(message.author)
                }
                else ->
                    message.channel.sendMessage(allEmbeds.first()).setupDeletable(message.author).setupControls(message.author, 0, allEmbeds)
            }
        } catch (e: FileNotFoundException) {
            message.channel.sendMessage("Couldn't find any articles for \"${terms.joinToString(" ")}\".")
        }
        if (it.channel !is PrivateChannel)
            it.delete()
    }.catch {
        type.close()
        message.sendError("An error occurred trying to look up the article.", it)
    }


}
