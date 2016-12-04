package com.novocode.ornate.theme

import java.net.{URI, URL}
import java.util.Collections

import better.files.File.OpenOptions
import com.novocode.ornate._
import com.novocode.ornate.commonmark.NodeExtensionMethods._
import better.files._
import com.novocode.ornate.commonmark._
import com.novocode.ornate.config.ConfigExtensionMethods.configExtensionMethods
import com.novocode.ornate.config.Global
import com.novocode.ornate.highlight.{HighlightResult, HighlightTarget}
import com.novocode.ornate.js.{CSSO, ElasticlunrSearch, NashornSupport}
import com.typesafe.config.{ConfigObject, ConfigRenderOptions}
import org.commonmark.renderer.NodeRenderer
import org.commonmark.renderer.html.HtmlRenderer
import org.commonmark.renderer.html.HtmlRenderer.HtmlRendererExtension
import org.commonmark.renderer.html.{HtmlNodeRendererContext, HtmlNodeRendererFactory}
import org.commonmark.node._
import play.twirl.api.{Html, HtmlFormat, Template1}

import scala.StringBuilder
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.io.Codec

/** Base class for Twirl-based HTML themes */
class HtmlTheme(global: Global) extends Theme(global) { self =>
  val mathJaxExclude = new FileMatcher(Seq(
    "/config/local/", "/docs/", "/test/", "/unpacked/", "*.md", "*.html", "*.txt", "*.json", ".*"
  ))

  val tc = global.userConfig.theme.config
  val suffix = ".html"
  val indexPage = tc.getStringOpt("global.indexPage")
  val minifyCSS = tc.getBooleanOr("global.minify.css")
  val minifyJS = tc.getBooleanOr("global.minify.js")
  val minifyHTML = tc.getBooleanOr("global.minify.html")

  def targetFile(uri: URI, base: File): File =
    uri.getPath.split('/').filter(_.nonEmpty).foldLeft(base) { case (f, s) => f / s }

  /** Render a heading with an ID. It can be overridden in subclasses as needed. */
  def renderAttributedHeading(n: AttributedHeading, c: HtmlNodeRendererContext): Unit = {
    val htag = s"h${n.getLevel}"
    val attrs = c.extendAttributes(n, Collections.emptyMap[String, String])
    if(n.id ne null) attrs.put("id", n.id)
    val classes = n.simpleAttrs.filter(_.startsWith(".")).map(_.drop(1))
    if(classes.nonEmpty) attrs.put("class", classes.mkString(" "))
    val wr = c.getWriter
    wr.line
    wr.tag(htag, attrs)
    n.children.toVector.foreach(c.render)
    wr.tag('/' + htag)
    wr.line
  }

  def renderAttributedBlockQuote(n: AttributedBlockQuote, c: HtmlNodeRendererContext): Unit = {
    val attrs = c.extendAttributes(n, Collections.emptyMap[String, String])
    if(n.id ne null) attrs.put("id", n.id)
    val classes = n.simpleAttrs.filter(_.startsWith(".")).map(_.drop(1))
    if(classes.nonEmpty) attrs.put("class", classes.mkString(" "))
    val wr = c.getWriter
    wr.line
    wr.tag("blockquote", attrs)
    wr.line
    n.children.toVector.foreach(c.render)
    wr.line
    wr.tag("/blockquote")
    wr.line
  }

  /** Render a tab view. The default implementation simply renders the content so that merged code blocks
    * look no different than regular code blocks. Themes can override this method to render the actual
    * tab view. */
  def renderTabView(pc: HtmlPageContext)(n: TabView, c: HtmlNodeRendererContext): Unit = {
    n.children.toVector.foreach {
      case i: TabItem =>
        i.children.toVector.foreach(c.render)
      case n => c.render(n)
    }
  }

  /** Render code that was run through the highlighter. This method is called for all fenced code blocks,
    * indented code blocks and inline code. It can be overridden in subclasses as needed. */
  def renderCode(n: Node, hlr: HighlightResult, c: HtmlNodeRendererContext, block: Boolean): Unit = {
    val langCode = hlr.language.map("language-" + _)
    val codeClasses = (if(block) hlr.preCodeClasses else hlr.codeClasses) ++ langCode
    val codeAttrs: Map[String, String] = (if(codeClasses.nonEmpty) Map("class" -> codeClasses.mkString(" ")) else Map.empty)
    val preAttrs: Map[String, String] = (if(hlr.preClasses.nonEmpty) Map("class" -> hlr.preClasses.mkString(" ")) else Map.empty)
    val wr = c.getWriter
    if(block) {
      wr.line
      wr.tag("pre", preAttrs.asJava)
    }
    wr.tag("code", codeAttrs.asJava)
    wr.raw(hlr.html.toString)
    wr.tag("/code")
    if(block) {
      wr.tag("/pre")
      wr.line
    }
  }

  def fencedCodeBlockRenderer(pc: HtmlPageContext) = SimpleHtmlNodeRenderer { (n: AttributedFencedCodeBlock, c: HtmlNodeRendererContext) =>
    val info = if(n.getInfo eq null) Vector.empty else n.getInfo.split(' ').filter(_.nonEmpty).toVector
    renderFencedCodeBlock(n, c, pc, info.headOption)
  }

  def renderFencedCodeBlock(n: AttributedFencedCodeBlock, c: HtmlNodeRendererContext, pc: HtmlPageContext, lang: Option[String]): Unit = lang match {
    case Some("mermaid") => renderMermaid(n, c, pc)
    case Some("texmath") => renderMath(n.getLiteral, c, pc, "tex", true)
    case Some("asciimath" | "math") => renderMath(n.getLiteral, c, pc, "asciimath", true)
    case Some("mathml") => renderMath(n.getLiteral, c, pc, "mml", true)
    case _ => renderRegularFencedCodeBlock(n, c, pc, lang)
  }

  def renderRegularFencedCodeBlock(n: AttributedFencedCodeBlock, c: HtmlNodeRendererContext, pc: HtmlPageContext, lang: Option[String]): Unit = {
    val hlr = global.highlighter.highlightTextAsHTML(n.getLiteral, lang, HighlightTarget.FencedCodeBlock, pc.page)
    hlr.css.foreach(u => pc.res.getURI(u, null, u.getPath.endsWith(".css"), false))
    renderCode(n, hlr.copy(language = lang.orElse(hlr.language)), c, true)
  }

  /** Render a Mermaid diagram block. This does not add any dependency on Mermaid to the generated site.
    * The method should be overwritten accordingly (unless a theme always adds it anyway). */
  def renderMermaid(n: AttributedFencedCodeBlock, c: HtmlNodeRendererContext, pc: HtmlPageContext): Unit = {
    val wr = c.getWriter
    wr.tag("div", Map("class" -> "mermaid", "id" -> pc.newID()).asJava)
    wr.text(n.getLiteral)
    wr.tag("/div")
  }

  def renderInlineMath(pc: HtmlPageContext)(n: InlineMath, c: HtmlNodeRendererContext): Unit =
    renderMath(n.literal, c, pc, n.language, false)

  def renderMathBlock(pc: HtmlPageContext)(n: MathBlock, c: HtmlNodeRendererContext): Unit =
    renderMath(n.literal, c, pc, n.language, true)

  /** Render a TeX math, MML or ASCIIMath block or inline element. The default implementation puts the code into a
    * "script" element with the proper language code (which should be one of "tex", "asciimath" and "mml").
    * Inline elements get a preceding "MathJax_Preview" span element, for block elements this is created as a div and
    * a "mode=display" annotation is added to the script. */
  def renderMath(code: String, c: HtmlNodeRendererContext, pc: HtmlPageContext, mathType: String, block: Boolean): Unit = {
    pc.requireMathJax()
    val wr = c.getWriter
    if(block && mathType == "asciimath") // Work around a MathJax bug: AsciiMath is always displayed inline
      wr.tag("div", Map("class" -> "MJXc-display", "style" -> "text-align: center").asJava)
    wr.tag(if(block) "div" else "span", Map("class" -> "MathJax_Preview").asJava)
    wr.text("[math]")
    wr.tag(if(block) "/div" else "/span")
    wr.tag("script", Map("type" -> ("math/" + mathType + (if(block) "; mode=display" else ""))).asJava)
    if(mathType == "math/mml") wr.raw(code)
    else wr.raw(Util.encodeScriptContent(code))
    wr.tag("/script")
    if(block && mathType == "asciimath") wr.tag("/div")
  }

  def indentedCodeBlockRenderer(pc: HtmlPageContext) = SimpleHtmlNodeRenderer { (n: IndentedCodeBlock, c: HtmlNodeRendererContext) =>
    val hlr = global.highlighter.highlightTextAsHTML(n.getLiteral, None, HighlightTarget.IndentedCodeBlock, pc.page)
    hlr.css.foreach(u => pc.res.getURI(u, null, u.getPath.endsWith(".css"), false))
    renderCode(n, hlr, c, true)
  }

  def inlineCodeRenderer(pc: HtmlPageContext) = SimpleHtmlNodeRenderer { (n: Code, c: HtmlNodeRendererContext) =>
    val hlr = global.highlighter.highlightTextAsHTML(n.getLiteral, None, HighlightTarget.InlineCode, pc.page)
    hlr.css.foreach(u => pc.res.getURI(u, null, u.getPath.endsWith(".css"), false))
    renderCode(n, hlr, c, false)
  }

  def emojiRenderer(pc: HtmlPageContext) = SimpleHtmlNodeRenderer { (n: Emoji, c: HtmlNodeRendererContext) =>
    val wr = c.getWriter
    if(n.uri ne null) {
      wr.raw(s"""<img class="emoji" title="${n.name}" alt="""")
      wr.text(n.unicode)
      wr.raw("""" src="""")
      wr.text(pc.slp.resolve(pc.page.uri, n.uri.toString, "image", false, true))
      wr.raw("""""/>""")
    } else {
      wr.raw(s"""<span class="emoji" title="${n.name}">""")
      wr.text(n.unicode)
      wr.raw("</span>")
    }
  }

  def renderers(pc: HtmlPageContext): Seq[HtmlNodeRendererFactory] = Seq(
    emojiRenderer(pc),
    SimpleHtmlNodeRenderer(renderAttributedBlockQuote _),
    SimpleHtmlNodeRenderer(renderAttributedHeading _),
    SimpleHtmlNodeRenderer(renderTabView(pc) _),
    SimpleHtmlNodeRenderer(renderInlineMath(pc) _),
    SimpleHtmlNodeRenderer(renderMathBlock(pc) _)
  )

  /** If MathJAX is needed by the page, add all resources and return the resolved main script URI and inline config. */
  def addMathJaxResources(pc: HtmlPageContext): Option[(URI, Option[ConfigObject])] = if(pc.needsMathJax) {
    val loadConfig = tc.getStringListOr("global.mathJax.loadConfig").mkString(",")
    val inlineConfig = tc.getConfigOpt("global.mathJax.inlineConfig").map(_.root())
    for(path <- NashornSupport.listAssets("mathjax", "/"))
      if(!mathJaxExclude.matchesPath(path))
        pc.res.get(s"webjar:/mathjax/$path", "mathjax/", minified=true)
    val u = pc.res.get(s"webjar:/mathjax/MathJax.js", "mathjax/", minified=true)
    val u2 = if(loadConfig.isEmpty) u else new URI(u.getScheme, u.getUserInfo, u.getHost, u.getPort, u.getPath, "config="+loadConfig, u.getFragment)
    Some(u2, inlineConfig)
  } else None

  def render(site: Site): Unit = {
    val staticResources = global.findStaticResources
    val staticResourceURIs = staticResources.iterator.map(_._2.getPath).toSet
    val siteResources = new mutable.HashMap[URL, ResourceSpec]

    site.pages.foreach { p =>
      val file = targetFile(p.uriWithSuffix(suffix), global.userConfig.targetDir)
      try {
        val templateName = global.userConfig.theme.getConfig(p.config).getString("template")
        logger.debug(s"Rendering page ${p.uri} to file $file with template ${templateName}")
        val resourceBaseURI = {
          val dir = tc.getString(s"global.resourceDir")
          Util.siteRootURI.resolve(if(dir.endsWith("/")) dir else dir + "/")
        }
        val pres = new PageResources(p, this, resourceBaseURI)
        val slp = new SpecialLinkProcessor(pres, site, suffix, indexPage, staticResourceURIs)
        slp(p)
        val pc = new HtmlPageContext(this, site, p, slp, pres)
        val renderer = renderers(pc).foldLeft(HtmlRenderer.builder()) { case (z, n) => z.nodeRendererFactory(n) }
          .nodeRendererFactory(fencedCodeBlockRenderer(pc))
          .nodeRendererFactory(indentedCodeBlockRenderer(pc))
          .nodeRendererFactory(inlineCodeRenderer(pc))
          .extensions(p.extensions.htmlRenderer.asJava).build()
        val pm = new HtmlPageModel(pc, renderer)
        val formatted = getTemplate(templateName).render(pm).body.trim
        siteResources ++= pc.res.mappings.map(r => (r.sourceURL, r))
        file.parent.createDirectories()
        val minifyInlineJS = minifyJS && !pc.needsMathJax // HtmlCompressor cannot handle non-JavaScript <script> tags
        val min =
          if(minifyHTML) Util.htmlCompressorMinimize(formatted, minimizeCss = minifyCSS, minimizeJs = minifyInlineJS) else formatted+'\n'
        file.write(min)(codec = Codec.UTF8)
      } catch { case ex: Exception =>
        logger.error(s"Error rendering page ${p.uri} to $file", ex)
      }
    }

    try createSearchIndex(site)
    catch { case ex: Exception => logger.error(s"Error creating search index", ex) }

    staticResources.foreach { case (sourceFile, uri) =>
      val file = targetFile(uri, global.userConfig.targetDir)
      logger.debug(s"Copying static resource $uri to file $file")
      try sourceFile.copyTo(file, overwrite = true)
      catch { case ex: Exception =>
        logger.error(s"Error copying static resource file $sourceFile to $file", ex)
      }
    }

    siteResources.valuesIterator.filter(_.sourceURI.getScheme != "site").foreach { rs =>
      val file = targetFile(rs.targetURI, global.userConfig.targetDir)
      logger.debug(s"Copying theme resource ${rs.sourceURL} to file $file")
      try {
        rs.minifiableType match {
          case Some("css") if minifyCSS => Util.copyToFileWithTextTransform(rs.sourceURL, file) { s =>
            try CSSO.minify(s) catch { case ex: Exception =>
              logger.error(s"Error minifying theme CSS file ${rs.sourceURI} with CSSO", ex)
              s
            }
          }
          case Some("js") if minifyJS => Util.copyToFileWithTextTransform(rs.sourceURL, file) { s =>
            try Util.closureMinimize(s, rs.sourceURI.toString) catch { case ex: Exception =>
              logger.error(s"Error minifying theme JS file ${rs.sourceURI} with Closure Compiler", ex)
              s
            }
          }
          case _ => Util.copyToFile(rs.sourceURL, file)
        }
      } catch { case ex: Exception =>
        logger.error(s"Error copying theme resource ${rs.sourceURL} to $file", ex)
      }
    }
  }

  protected def createSearchIndex(site: Site): Unit = {
    if(tc.hasPath("global.pages.search") && tc.hasPath("global.searchIndex")) {
      val searchPage = Util.siteRootURI.resolve(tc.getString("global.pages.search"))
      val searchIndexFile = targetFile(Util.siteRootURI.resolve(tc.getString("global.searchIndex")), global.userConfig.targetDir)
      logger.debug("Writing search index to "+searchIndexFile)
      val exclude = new FileMatcher(tc.getStringListOr("global.searchExcludePages").toVector)
      val excerptLength = tc.getIntOr("global.searchExcerptLength")
      val idx = ElasticlunrSearch.createIndex
      site.pages.foreach { p =>
        if(exclude.matchesPath(p.uri.getPath))
          logger.debug(s"Excluding page ${p.uri} from search index")
        else {
          val body = NodeUtil.extractText(p.doc).trim
          val title = site.findTocEntry(p).flatMap(_.title).orElse(p.section.title).getOrElse("").trim
          val excerpt = if(excerptLength > 0) {
            val bodyOnly = NodeUtil.extractText(p.doc, withCodeBlocks=false, withFirstHeading=false, limit=excerptLength).trim
            val short = bodyOnly.indexOf(' ', excerptLength) match {
              case -1 => bodyOnly
              case n => bodyOnly.substring(0, n)
            }
            val dots = short.reverseIterator.takeWhile(_ == '.').length
            val noDots = if(dots == 0) short else short.substring(0, short.length-dots)
            noDots + "..."
          } else ""
          val link = Util.rewriteIndexPageLink(Util.relativeSiteURI(searchPage, p.uriWithSuffix(suffix)), indexPage).toString
          val keywords = p.config.getStringOr("meta.keywords", "")
          idx.add(title, body, excerpt, keywords, link)
        }
      }
      implicit val codec = Codec.UTF8
      searchIndexFile.write("window._searchIndex = "+idx.toJSON+";")
    }
  }

  type Template = Template1[HtmlPageModel, HtmlFormat.Appendable]
  private[this] val templateBase = getClass.getName.replaceAll("\\.[^\\.]*$", "")
  private[this] val templates = new mutable.HashMap[String, Template]
  def getTemplate(name: String) = templates.getOrElseUpdate(name, {
    val className = s"$templateBase.$name"
    logger.debug("Creating template from class "+className)
    Class.forName(className).newInstance().asInstanceOf[Template]
  })
}

/** The page context is available for preprocessing a page */
class HtmlPageContext(val theme: HtmlTheme, site: Site, val page: Page, val slp: SpecialLinkProcessor, val res: PageResources) {
  private[this] var last = -1
  private[this] var _needsJavaScript, _needsMathJax = false
  def newID(): String = {
    last += 1
    s"_id$last"
  }
  def needsJavaScript: Boolean = _needsJavaScript
  def requireJavaScript(): Unit = _needsJavaScript = true
  def needsMathJax: Boolean = _needsMathJax
  def requireMathJax(): Unit = _needsMathJax = true

  private lazy val pageTC = theme.global.userConfig.theme.getConfig(page.config)
  def pageConfig(path: String): Option[String] = page.config.getStringOpt(path)
  def themeConfig(path: String): Option[String] = pageTC.getStringOpt(path)
  def themeConfigBoolean(path: String): Option[Boolean] = pageTC.getBooleanOpt(path)
  lazy val siteNav: Option[Vector[ExpandTocProcessor.TocItem]] = themeConfig("siteNav") match {
    case Some(uri) =>
      val tocBlock = SpecialImageProcessor.parseTocURI(uri, theme.global.userConfig)
      Some(ExpandTocProcessor.buildTocTree(tocBlock, site.toc, page))
    case None => None
  }

  def searchLink: Option[String] = theme.tc.getStringOpt("global.pages.search").map { uri =>
    Util.rewriteIndexPageLink(Util.relativeSiteURI(page.uri, Util.replaceSuffix(Util.siteRootURI.resolve(uri), ".md", ".html")), theme.indexPage).toString
  }
  def searchIndex: Option[String] = theme.tc.getStringOpt("global.searchIndex").map { uri =>
    Util.relativeSiteURI(page.uri, Util.siteRootURI.resolve(uri)).toString
  }
  def resolveLink(dest: String): String = slp.resolve(page.uri, dest, "link", true, false)

  def sections: Vector[Section] = page.section.children

  def stringNode(name: String): Option[Node] = themeConfig(s"strings.$name").map { md =>
    val snippet = page.parseAndProcessSnippet(md)
    slp(snippet)
    snippet.doc
  }
}

/** The page model is available for rendering a preprocessed page with a template. Creating the HtmlPageModel
  * forces the content to be rendered. */
class HtmlPageModel(val pc: HtmlPageContext, renderer: HtmlRenderer) {
  val title = HtmlFormat.escape(pc.page.section.title.getOrElse(""))
  val content = HtmlFormat.raw(renderer.render(pc.page.doc))
  private[this] val mathJaxResources = pc.theme.addMathJaxResources(pc)
  val mathJaxMain: Option[URI] = mathJaxResources.map(_._1)
  val mathJaxInline: Option[Html] = mathJaxResources.flatMap(_._2).flatMap { cv =>
    if(cv.isEmpty) None
    else Some(HtmlFormat.raw(cv.render(ConfigRenderOptions.concise())))
  }
  val mathJaxSkipStartupTypeset =
    mathJaxResources.flatMap(_._2).map(_.toConfig.getBooleanOr("skipStartupTypeset")).getOrElse(false)
  def stringHtml(name: String): Option[Html] = pc.stringNode(name).map(n => HtmlFormat.raw(renderer.render(n)))
  def stringText(name: String): Option[Html] = pc.stringNode(name).map(n => HtmlFormat.escape(NodeUtil.extractText(n)))
}
