package isabelle.jedit

import isabelle.{GUI_Thread, Markup, Rendering, Text, XML, Value}
import javax.swing.JOptionPane
import org.gjt.sp.jedit.View
import org.gjt.sp.jedit.textarea.JEditTextArea
import org.gjt.sp.jedit.buffer.JEditBuffer
import isabelle.Document
import org.gjt.sp.jedit.jEdit
import org.gjt.sp.jedit.io.VFSManager

object Refactor {
  def rename(view: View): Unit =
    GUI_Thread.now {
      val text_area = view.getTextArea()

      Document_View.get(text_area).foreach { doc_view =>
        val rendering = Document_View.rendering(doc_view)
        val snapshot = rendering.snapshot

        // May be multiple overlapping entities
        val entities = entity_at_caret(text_area, snapshot)

        // Id of entity under caret
        val canonical_id: Option[Long] =
          entities.collectFirst { case ("constant", _, Some(id)) => id }

        val all_ids: Set[Long] =
          entities.flatMap { case (_, _, id_opt) => id_opt }.toSet

        val target_name: Option[String] =
          entities.collectFirst { case ("constant", name, _) => name }

        // List of ranges of entities matching ID
        val rangeList = find_all_occurrences(canonical_id, all_ids, target_name)

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
        // e.g. "Scratch.myDouble" -> "myDouble"
        val oldShortName: Option[String] = target_name.map { t =>
          val dot = t.lastIndexOf('.')
          if (dot >= 0) t.substring(dot + 1) else t
        }



        // Heuristic checking of matches in unchecked files
        // DOES NOT WORK, DEL LATER
        oldShortName.foreach { name =>
          if (load_unchecked_candidates(name, view))
            while (PIDE.session.get_state().stable_tip_version.isEmpty)
              Thread.sleep(200)
        }

        // Get new entity name from user input
        val newName: Option[String] = Option(
          JOptionPane.showInputDialog(
            view,
            s"ID: ${canonical_id.getOrElse("none")}\nMatches:\n$rangesStr\n\nEnter replacement name:"
          )
        )

        // Do rename across all buffers
        (newName, oldShortName) match {
          case (Some(name), Some(oldName)) if name.nonEmpty =>
            rangeList.foreach { case (node_name, ranges) =>
              val path = node_name.node
              val buffer = jEdit.openFile(view, path)
              VFSManager.waitForRequests()
              buffer.beginCompoundEdit()
              try {
                rename_in_buffer(buffer, ranges, oldName, name)
              } finally {
                buffer.endCompoundEdit()
              }
            }
          case _ =>
          // User cancelled or left blank
        }
      }

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

  private def def_site_ref_id(markup: Markup): Option[Long] =
    for {
      ref_str <- markup.properties.collectFirst { case ("ref", v) => v }
      ref_id <- Value.Long.unapply(ref_str)
      _ <- markup.properties.collectFirst { case ("def_offset", _) => () }
    } yield ref_id

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

  def find_all_occurrences(
      target_id: Option[Long],
      all_ids: Set[Long],
      target_name: Option[String]
  ): List[(Document.Node.Name, List[Text.Range])] = {
    val state = PIDE.session.get_state()
    (for {
      id <- target_id.toList // exits early if no id
      version <- state.stable_tip_version.toList // exits early if no version
      node_name <- version.nodes.topological_order
      snapshot = state.snapshot(node_name)
      ranges = find_occurrences(id, all_ids, target_name, snapshot)
      if ranges.nonEmpty
    } yield (node_name, ranges))
  }

  // DOES NOT WORK, DEL LATER
  def load_unchecked_candidates(name: String, view: View): Boolean = {
    val state = PIDE.session.get_state()
    val known = PIDE.resources.session_base.known_theories
    val checked_nodes = state.stable_tip_version
      .map(_.nodes.topological_order.toSet)
      .getOrElse(Set.empty)
    val (_, unloaded) = known.values.partition(e => checked_nodes.contains(e.name))

    val candidates = unloaded.filter { entry =>
      val src = scala.io.Source.fromFile(entry.name.node)
      try src.mkString.contains(name)
      finally src.close()
    }

    candidates.foreach(n => jEdit.openFile(view, n.name.node))
    candidates.nonEmpty
  }

}
