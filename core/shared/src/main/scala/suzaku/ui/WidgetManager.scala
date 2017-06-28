package suzaku.ui

import arteria.core._
import suzaku.platform.{Logger, Platform}
import suzaku.ui.UIProtocol._
import suzaku.ui.layout.LayoutProperty
import suzaku.ui.style.StyleClassRegistry.StyleClassRegistration
import suzaku.ui.style.{ExtendClasses, InheritClasses, RemapClasses, StyleBaseProperty, WidgetClasses}

import scala.collection.immutable.IntMap
import scala.collection.mutable

abstract class WidgetManager(logger: Logger, platform: Platform)
    extends MessageChannelHandler[UIProtocol.type]
    with WidgetParent {
  import WidgetManager._

  case class RegisteredStyle(props: List[StyleBaseProperty],
                             inherited: List[Int],
                             remaps: IntMap[List[Int]],
                             widgetClasses: IntMap[List[Int]])

  private var widgetClassMap       = IntMap.empty[String]
  private var registeredWidgets    = Map.empty[String, WidgetBuilder[_ <: Protocol]]
  private var builders             = IntMap.empty[WidgetBuilder[_ <: Protocol]]
  private var uiChannel: UIChannel = _
  protected val nodes              = mutable.LongMap[WidgetNode](-1L -> WidgetNode(emptyWidget(-1), Nil, -1))
  protected var rootNode           = Option.empty[WidgetNode]
  protected val registeredStyles   = mutable.LongMap.empty[RegisteredStyle]
  protected var themes             = Vector.empty[(Int, Map[Int, List[Int]])]
  protected var activeTheme        = IntMap.empty[List[Int]]
  protected var frameRequested     = false

  override def establishing(channel: MessageChannel[ChannelProtocol]) =
    uiChannel = channel

  def registerWidget(id: String, builder: WidgetBuilder[_ <: Protocol]): Unit =
    registeredWidgets += id -> builder

  def registerWidget(clazz: Class[_], builder: WidgetBuilder[_ <: Protocol]): Unit =
    registeredWidgets += clazz.getName -> builder

  def buildWidget(widgetClass: Int,
                  widgetId: Int,
                  channelId: Int,
                  globalId: Int,
                  channelReader: ChannelReader): Option[Widget] = {
    val builder = builders.get(widgetClass) orElse {
      // update builder map from registered widgets
      val b = widgetClassMap.get(widgetClass).flatMap(registeredWidgets.get)
      b.foreach(v => builders += widgetClass -> v)
      b
    }
    builder.map(builder => builder.materialize(widgetId, widgetClass, channelId, globalId, uiChannel, channelReader))
  }

  def shouldRenderFrame: Boolean = {
    if (frameRequested) {
      frameRequested = false
      true
    } else {
      false
    }
  }

  def setParent(node: WidgetNode, parent: WidgetParent): Unit = {
    // only set parent if the parent has a parent
    if (parent.hasParent) {
      node.widget.setParent(parent)
      node.children.foreach(c => setParent(nodes(c), node.widget))
    }
  }

  def reapplyStyles(id: Int): Unit = {
    nodes.get(id) match {
      case Some(node) =>
        node.widget.reapplyStyles()
        node.children.foreach(reapplyStyles)
      case None =>
      // no action
    }
  }

  def rebuildThemes(themes: Seq[(Int, Map[Int, List[Int]])]): Unit = {
    // join themes to form the active theme
    activeTheme = themes.foldLeft(IntMap.empty[List[Int]]) {
      case (act, (_, styleMap)) =>
        styleMap.foldLeft(act) {
          case (current, (widget, styleClasses)) =>
            current.updated(widget, (current.getOrElse(widget, Nil) ++ styleClasses).distinct)
        }
    }
    // reapply styles as theme changes may affect them
    rootNode.foreach(n => reapplyStyles(n.widget.widgetId))
  }

  def applyTheme(widgetClassId: Int): List[Int] =
    activeTheme.getOrElse(widgetClassId, Nil)

  def getStyle(styleId: Int): Option[RegisteredStyle] =
    registeredStyles.get(styleId)

  def getStyleRemaps(styleId: Int): Option[Map[Int, List[Int]]] =
    registeredStyles.get(styleId).map(_.remaps)

  def getStyleWidgetClasses(styleId: Int): Option[Map[Int, List[Int]]] =
    registeredStyles.get(styleId).map(_.widgetClasses)

  override def process = {
    case MountRoot(widgetId) =>
      nodes.get(widgetId) match {
        case Some(node) =>
          rootNode = Some(node)
          setParent(node, this)
          mountRoot(node.widget.artifact)
        case None =>
          throw new IllegalArgumentException(s"Widget with id $widgetId has no node")
      }

    case RequestFrame =>
      frameRequested = true

    case SetChildren(widgetId, children) =>
      logger.debug(s"Setting [$children] as children of [$widgetId]")
      val childNodes = children.flatMap(nodes.get(_))
      nodes.get(widgetId).foreach { node =>
        node.widget.setChildren(childNodes.map(_.widget).asInstanceOf[Seq[node.widget.W]])
        childNodes.foreach(c => setParent(c, node.widget))
        nodes.update(widgetId, node.copy(children = children))
      }

    case UpdateChildren(widgetId, ops) =>
      logger.debug(s"Updating children of [$widgetId] with [$ops]")
      nodes.get(widgetId).foreach { node =>
        // play operations on node children sequence first
        val cur    = node.children.toBuffer
        var curIdx = 0
        ops.foreach {
          case NoOp(n) =>
            curIdx += n
          case InsertOp(id) =>
            val widget = nodes(id).widget
            widget.setParent(node.widget)
            cur.insert(curIdx, id)
            curIdx += 1
          case RemoveOp(n) =>
            cur.remove(curIdx, n)
          case MoveOp(idx) =>
            cur.insert(curIdx, cur.remove(idx))
            curIdx += 1
          case ReplaceOp(id) =>
            val widget = nodes(id).widget
            widget.setParent(node.widget)
            cur(curIdx) = id
            curIdx += 1
        }
        // let widget update its child structure
        node.widget.updateChildren(ops, widgetId => nodes(widgetId).widget.asInstanceOf[node.widget.W])
        // update the widget node
        nodes.update(widgetId, node.copy(children = cur))
      }

    case AddStyles(styles) =>
      logger.debug(s"Received styles ${styles.reverse}")
      var dirtyStyles = false
      val baseStyles = styles.reverse.map {
        case StyleClassRegistration(styleId, styleName, props) =>
          // extract inheritance information
          val inherits = props.collect {
            case i: InheritClasses => i
          }
          val extend = props.collect {
            case e: ExtendClasses => e
          }
          val baseProps = props.collect {
            case prop: StyleBaseProperty => prop
          }
          val remaps = props
            .collect {
              case remap: RemapClasses =>
                remap.styleMap.map { case (key, value) => key.id -> value.map(_.id) }(collection.breakOut): IntMap[
                  List[Int]]
            }
            .foldLeft(IntMap.empty[List[Int]])(_ ++ _)
          val widgetClasses = props
            .collect {
              case widgetClass: WidgetClasses =>
                widgetClass.styleMapping.map { case (key, value) => key -> value.map(_.id) }(collection.breakOut): IntMap[
                  List[Int]]
            }
            .foldLeft(IntMap.empty[List[Int]])(_ ++ _)

          val extProps = extend.flatMap(_.styles.flatMap(sc => registeredStyles(sc.id).props))
          val inherited = if (inherits.nonEmpty) {
            dirtyStyles = true
            val resolved = inherits.flatMap(_.styles.flatMap(s => registeredStyles(s.id).inherited)).distinct
            resolved :+ styleId
          } else styleId :: Nil
          val allProps = extProps ::: baseProps
          registeredStyles.update(styleId, RegisteredStyle(allProps, inherited, remaps, widgetClasses))

          (styleId, styleName, allProps)
      }
      (dirtyStyles, rootNode) match {
        case (true, Some(node)) =>
          // set parent recursively to apply changed styles
          setParent(node, this)
        case _ => // nothing to update
      }
      addStyles(baseStyles)

    case AddLayoutIds(ids) =>
    // TODO store somewhere

    case ActivateTheme(themeId, theme) =>
      themes :+= (themeId, theme)
      rebuildThemes(themes)

    case DeactivateTheme(themeId) =>
      themes = themes.filterNot(_._1 == themeId)
      rebuildThemes(themes)

    case RegisterWidgetClass(className, classId) =>
      widgetClassMap += classId -> className
  }

  override def materializeChildChannel(channelId: Int,
                                       globalId: Int,
                                       parent: MessageChannelBase,
                                       channelReader: ChannelReader): MessageChannelBase = {
    import boopickle.Default._
    // read the component creation data
    val CreateWidget(widgetClass, widgetId) = channelReader.read[CreateWidget]
    val widgetClassName                     = widgetClassMap(widgetClass)
    logger.debug(f"Building widget $widgetClassName on channel [$channelId, $globalId%08x]")
    try {
      buildWidget(widgetClass, widgetId, channelId, globalId, channelReader) match {
        case Some(widget) =>
          // add a node for the component
          nodes.update(widgetId, WidgetNode(widget, Vector.empty, channelId))
          widget.channel
        case None =>
          throw new IllegalAccessException(s"Unable to materialize a widget '$widgetClassName'")
      }
    } catch {
      case e: Exception =>
        logger.error(s"Unhandled exception while building widget $widgetClassName: $e")
        throw e
    }
  }

  override def channelWillClose(id: Int): Unit = {
    logger.debug(s"Widget [$id] removed")
    nodes -= id
  }

  override def hasParent: Boolean = true

  override def resolveStyleMapping(wClsId: Int, ids: List[Int]): List[Int] = {
    applyTheme(wClsId) ::: ids
  }

  override def resolveStyleInheritance(ids: List[Int]): List[Int] = {
    val res = ids.flatMap(id => registeredStyles(id).inherited)
    res
  }

  override def resolveLayout(widget: Widget, layoutProperties: List[LayoutProperty]): Unit = {}

  override def getStyleMapping: IntMap[List[Int]] = IntMap.empty

  override def getWidgetStyleMapping: IntMap[List[Int]] = activeTheme

  protected def emptyWidget(widgetId: Int): Widget

  protected def mountRoot(node: WidgetArtifact): Unit

  protected def addStyles(styles: List[(Int, String, List[StyleBaseProperty])]): Unit
}

object WidgetManager {
  case class WidgetNode(widget: Widget, children: Seq[Int], channelId: Int)
}
