package isabelle.jedit

import isabelle.{GUI_Thread, Markup, Rendering, Text, XML, Value, Headless, Document, Sessions}
import javax.swing.JOptionPane
import java.io.{File => JFile}
import org.gjt.sp.jedit.View
import org.gjt.sp.jedit.textarea.JEditTextArea
import org.gjt.sp.jedit.buffer.JEditBuffer
import org.gjt.sp.jedit.jEdit
import org.gjt.sp.jedit.io.VFSManager

object Refactor {
  def rename(view: View): Unit = {
    val (target_name, oldShortName, current_file) =
      GUI_Thread.now {
        val text_area = view.getTextArea()

        val entities =
          (for {
            doc_view <- Document_View.get(text_area)
            snapshot = Document_View.rendering(doc_view).snapshot
          } yield entity_at_caret(text_area, snapshot)).getOrElse(Nil)

        val target_name = entities.collectFirst { case ("constant", name, _) => name }
        val oldShortName = target_name.map { t =>
          val dot = t.lastIndexOf('.')
          if (dot >= 0) t.substring(dot + 1) else t
        }
        val current_file = view.getBuffer.getPath
        (target_name, oldShortName, current_file)
      }

    val threadName = s"isabelle-rename-${target_name.getOrElse("unknown")}"
    new Thread(() => {
      val options = PIDE.options.value

      val dir = new JFile(current_file).getParent
      val extra_dirs = find_session_dir(new JFile(dir)).toList
      val sessions =
        JEdit_Session.sessions_structure(dirs = JEdit_Session.session_dirs ::: extra_dirs)
      // DEBUG
      System.err.println(s"session_dirs: ${JEdit_Session.session_dirs}")
      System.err.println(s"canonical_dir: ${new JFile(dir).getCanonicalFile}")
      System.err.println(s"known sessions: ${sessions.session_directories}")
      //
      val project_session = sessions.session_directories
        .get(new JFile(dir).getCanonicalFile)
        .getOrElse(JEdit_Session.logic_name(options))

      val info = sessions(project_session)
      val parent = info.parent.getOrElse(JEdit_Session.logic_name(options))

      val theory_short_names = info.theories.flatMap(_._2.map(_._1))
      val bg = Sessions.background(
        options,
        session = parent,
        dirs = JEdit_Session.session_dirs ::: extra_dirs
      )

      val resources = Headless.Resources(options, bg)
      val progress = new isabelle.Progress
      val master_dir = info.dir.implode

      System.err.println(s"project_session: $project_session")
      System.err.println(s"parent: $parent")
      System.err.println(s"theory_short_names: $theory_short_names")
      System.err.println(s"master_dir: $master_dir")

      val session = resources.start_session(progress = progress)

      val theory_names = resources.session_base.proper_session_theories.map(_.theory)
      val result =
        session.use_theories(theory_short_names, master_dir = master_dir, progress = progress)

      val defining_theory = target_name.map(n => n.take(n.lastIndexOf('.')))

      val defining_node = result.nodes.find { case (name, _) =>
        val short = name.theory.drop(name.theory.lastIndexOf('.') + 1)
        defining_theory.contains(short)
      }

      // DEBUG
      System.err.println(s"target_name: $target_name")
      System.err.println(s"defining_theory: $defining_theory")
      System.err.println(s"result.ok: ${result.ok}")
      System.err.println(s"result.nodes: ${result.nodes.map(_._1.theory)}")
      System.err.println(s"found theory: $defining_node")

      //

      val (headless_id, all_ids) = defining_node match {
        case None => (None, Set.empty[Long])
        case Some((name, _)) =>
          val snapshot = result.snapshot(name)
          val id = entity_by_name("constant", target_name, snapshot)
          val ids = collect_all_entity_ids(target_name, snapshot)
          (id, ids ++ id.toSet)
      }

      val rangeList = for {
        id <- headless_id.toList
        (name, _) <- result.nodes
        snapshot = result.snapshot(name)
        ranges = find_occurrences(id, all_ids, target_name, snapshot)
        if ranges.nonEmpty
      } yield (name, ranges)

      session.stop()

      GUI_Thread.now {

        // Display in popup
        val rangesStr = {
          if (rangeList.isEmpty) "  (none)"
          else
            rangeList
              .map { case (node_name, ranges) =>
                val header = s"  ${node_name.theory}:"
                val lines = ranges.map(r => s"    [${r.start}, ${r.stop})").mkString("\n")
                s"$header\n$lines"
              }
              .mkString("\n")
        }
        // Get new entity name from user input
        val newName: Option[String] = Option(
          JOptionPane.showInputDialog(
            view,
            s"ID: ${headless_id.getOrElse("none")}\nMatches:\n$rangesStr\n\nEnter replacement name:"
          )
        )

        // Do rename across all buffers
        (newName, oldShortName) match {
          case (Some(name), Some(oldName)) if name.nonEmpty =>
            rangeList.foreach { case (node_name, ranges) =>
              val path = isabelle.Path.explode(node_name.node)
              val text = isabelle.File.read(path)
              val result = ranges.sortBy(_.start).reverse.foldLeft(text) { (t, r) =>
                t.substring(0, r.start) + t.substring(r.start, r.stop).replace(oldName, name) + t
                  .substring(r.stop)
              }
              isabelle.File.write(path, result)
            }
            // Reload any open buffers
            rangeList.foreach { case (node_name, _) =>
              val buf = jEdit.getBuffer(node_name.node)
              if (buf != null) buf.reload(view)
            }
          case _ =>
          // User cancelled or left blank
        }

      }
    }threadName).start()
  }

  def rename_in_buffer(
      buffer: JEditBuffer,
      ranges: List[Text.Range],
      oldName: String,
      newName: String
  ): Unit =
    ranges.sortBy(_.start).reverse.foreach { r =>
      val text = buffer.getText(r.start, r.stop - r.start)
      val replaced = text.replace(oldName, newName)
      buffer.remove(r.start, r.stop - r.start)
      buffer.insert(r.start, replaced)
    }

  def entity_at_caret(
      text_area: JEditTextArea,
      snapshot: Document.Snapshot
  ): List[(String, String, Option[Long])] = {
    // Find entity ID under caret
    val caret_range = JEdit_Lib.caret_range(text_area)
    snapshot
      .cumulate[List[(String, String, Option[Long])]](
        caret_range,
        Nil,
        Rendering.entity_elements,
        _ => { case (acc, Text.Info(_, XML.Elem(markup, _))) =>
          (Markup.Entity.unapply(markup), Markup.Entity.Occ.unapply(markup)) match {
            case (Some((kind, name)), occ_id) => Some((kind, name, occ_id) :: acc)
            case _                            => None
          }
        }
      )
      .flatMap(_.info)
  }

  def entity_by_name(
      kind: String,
      target_name: Option[String],
      snapshot: Document.Snapshot
  ): Option[Long] = target_name match {
    case None              => None
    case Some(target_name) =>
      // DEBUG
      snapshot.cumulate[Unit](
        Text.Range(0, snapshot.node.source.length),
        (),
        Rendering.entity_elements,
        _ => { case (_, Text.Info(_, XML.Elem(markup, _))) =>
          System.err.println(
            s"  entity: ${Markup.Entity.unapply(markup)} occ: ${Markup.Entity.Occ.unapply(markup)}"
          )
          None
        }
      )
      //
      snapshot
        .cumulate[Option[Long]](
          Text.Range(0, snapshot.node.source.length),
          None,
          Rendering.entity_elements,
          _ => {
            case (None, Text.Info(_, XML.Elem(markup, _))) =>
              (Markup.Entity.unapply(markup), Markup.Entity.Occ.unapply(markup)) match {
                case (Some((`kind`, `target_name`)), Some(id)) => Some(Some(id))
                case _                                         => None
              }
            case _ => None
          }
        )
        .flatMap(_.info)
        .headOption
  }

  private def def_site_ref_id(markup: Markup): Option[Long] =
    for {
      ref_str <- markup.properties.collectFirst { case ("ref", v) => v }
      ref_id <- Value.Long.unapply(ref_str)
      _ <- markup.properties.collectFirst { case ("def_offset", _) => () }
    } yield ref_id

  def find_session_dir(start: JFile): Option[isabelle.Path] = {
    var d = start
    var found: Option[JFile] = None
    while (d != null) {
      if (new JFile(d, "ROOTS").exists())
        found = Some(d)
      d = d.getParentFile
    }
    // Fall back to ROOT if no ROOTS found
    if (found.isEmpty) {
      d = start
      while (d != null) {
        if (new JFile(d, "ROOT").exists())
          return Some(isabelle.Path.explode(d.getPath))
        d = d.getParentFile
      }
    }
    found.map(f => isabelle.Path.explode(f.getPath))
  }

  def find_occurrences(
      target_id: Long,
      all_ids: Set[Long],
      target_name: Option[String],
      snapshot: Document.Snapshot
  ): List[Text.Range] = {
    val full_range = Text.Range(0, snapshot.node.source.length)

    val nameMatches: String => Boolean = name =>
      target_name.exists(t => t == name || t.endsWith("." + name))

    // WARNING: This is brittle, I'm matching auto gen'd names by heuristic
    val matchesDerived: String => Boolean = name =>
      target_name.exists { t =>
        // Unqualified: "myDouble_def" starts with "myDouble_"
        name.startsWith(t + "_") ||
        // Qualified: "Scratch.myDouble_def" contains ".myDouble_"
        name.contains("." + t + "_")
      }

    val matches: Markup => Boolean = markup =>
      Markup.Entity.Occ.unapply(markup).contains(target_id) ||
        def_site_ref_id(markup).exists(all_ids.contains) ||
        Markup.Entity.unapply(markup).exists {
          case ("bound", name) => nameMatches(name)
          case ("fact", name)  => matchesDerived(name)
          case _               => false
        }
    snapshot
      .cumulate[List[Text.Range]](
        full_range,
        Nil,
        Rendering.entity_elements,
        _ => {
          case (acc, Text.Info(range, XML.Elem(markup, _))) if matches(markup) =>
            Some(range :: acc)

          case _ => None

        }
      )
      .flatMap(_.info)
      .distinct // guard against entity matching both conds
  }

  def collect_all_entity_ids(
      target_name: Option[String],
      snapshot: Document.Snapshot
  ): Set[Long] = {
    val shortName = target_name.map(n => n.drop(n.lastIndexOf('.') + 1))
    snapshot
      .cumulate[Set[Long]](
        Text.Range(0, snapshot.node.source.length),
        Set.empty,
        Rendering.entity_elements,
        _ => { case (acc, Text.Info(_, XML.Elem(markup, _))) =>
          val defId = markup.properties
            .collectFirst { case ("def", v) => v }
            .flatMap(Value.Long.unapply)
          val nameMatch = Markup.Entity.unapply(markup).exists { case (_, n) =>
            target_name.contains(n) || shortName.contains(n)
          }
          val noKindNameMatch = !markup.properties.exists(_._1 == "kind") &&
            defId.isDefined &&
            markup.properties
              .collectFirst { case ("name", v) => v }
              .exists(n => shortName.contains(n))
          if ((nameMatch || noKindNameMatch) && defId.isDefined)
            Some(acc + defId.get)
          else None
        }
      )
      .flatMap(_.info)
      .toSet
  }

}
