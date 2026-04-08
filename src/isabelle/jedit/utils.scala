package isabelle.jedit

import isabelle.{Document, Markup, Rendering, Value, Text, XML, Sessions, GUI_Thread}
import org.gjt.sp.jedit.textarea.JEditTextArea
import java.io.{File => JFile}
import javax.swing.JOptionPane
import org.gjt.sp.jedit.View
object MarkupUtils {


  def find_markup(
    snapshot: Document.Snapshot,
    elements: Markup.Elements,
    matches: Markup => Boolean
  ): List[Text.Range]  = {
    val full_range = Text.Range(0, snapshot.node.source.length)

    snapshot
      .cumulate[List[Text.Range]](
        full_range,
        Nil,
        elements,
        _ => {
          case (acc, Text.Info(range, XML.Elem(markup, _))) if matches(markup) =>
            Some(range :: acc)
          case _ => None
        }
      )
      .flatMap(_.info)
      .distinct
  }
  def short_name(qualified: String): String = {
    val dot = qualified.lastIndexOf('.')
    if (dot >= 0) qualified.substring(dot + 1) else qualified
  }
  def ref_to_def_id(markup: Markup): Option[Long] = {
    for {
      ref_str <- markup.properties.collectFirst { case ("ref", v) => v }
      ref_id <- Value.Long.unapply(ref_str)
      _ <- markup.properties.collectFirst { case ("def_offset", _) => () }
    } yield ref_id
  }

  /* Entity lookup */

  def entity_by_name(
      kind: String,
      target_name: Option[String],
      snapshot: Document.Snapshot
  ): Option[Long] = target_name.flatMap { name =>
    snapshot
      .cumulate[Option[Long]](
        Text.Range(0, snapshot.node.source.length),
        None,
        Rendering.entity_elements,
        _ => {
          case (None, Text.Info(_, XML.Elem(markup, _))) =>
            (Markup.Entity.unapply(markup), Markup.Entity.Occ.unapply(markup)) match {
              case (Some((`kind`, `name`)), Some(id)) => Some(Some(id))
              case _                                  => None
            }
          case _ => None
        }
      )
      .flatMap(_.info)
      .headOption
  }
  def collect_all_entity_ids(
      target_name: Option[String],
      snapshot: Document.Snapshot
  ): Set[Long] = {
    val short = target_name.map(short_name)
    snapshot
      .cumulate[Set[Long]](
        Text.Range(0, snapshot.node.source.length),
        Set.empty,
        Rendering.entity_elements,
        _ => { case (acc, Text.Info(_, XML.Elem(markup, _))) =>
          val def_id = markup.properties
            .collectFirst { case ("def", v) => v }
            .flatMap(Value.Long.unapply)
          val name_match = Markup.Entity.unapply(markup).exists { case (_, n) =>
            target_name.contains(n) || short.contains(n)
          }
          val no_kind_name_match = !markup.properties.exists(_._1 == "kind") &&
            def_id.isDefined &&
            markup.properties
              .collectFirst { case ("name", v) => v }
              .exists(n => short.contains(n))
          if ((name_match || no_kind_name_match) && def_id.isDefined)
            Some(acc + def_id.get)
          else None
        }
      )
      .flatMap(_.info)
      .toSet
  }

}

object JEditUtils {

  def entity_at_caret(
      text_area: JEditTextArea,
      snapshot: Document.Snapshot
  ): List[(String, String, Option[Long])] = {
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

  /* UI */

  def progress_dialog(
      view: View
  ): (javax.swing.JDialog, javax.swing.JLabel) = {
    val dialog = new javax.swing.JDialog(view, "Renaming…", true)
    val label = new javax.swing.JLabel("Loading theories…")
    val progress_bar = new javax.swing.JProgressBar()
    progress_bar.setIndeterminate(true)
    val panel = new javax.swing.JPanel()
    panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS))
    panel.setBorder(javax.swing.BorderFactory.createEmptyBorder(20, 20, 20, 20))
    panel.add(label)
    panel.add(javax.swing.Box.createVerticalStrut(10))
    panel.add(progress_bar)
    dialog.add(panel)
    dialog.setDefaultCloseOperation(javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE)
    dialog.pack()
    dialog.setLocationRelativeTo(view)
    (dialog, label)
  }
  def theory_progress(label: javax.swing.JLabel): isabelle.Progress =
    new isabelle.Progress {
      override def output(msgs: isabelle.Progress.Output): Unit =
        for (msg <- msgs) msg match {
          case thy: isabelle.Progress.Theory =>
            GUI_Thread.later { label.setText(s"Checking ${thy.theory}…") }
          case _ =>
        }
    }

}

object SessionUtils {

  /** Walk upward from `start` looking for a session root directory. Prefers directories containing
    * ROOTS; falls back to ROOT.
    */
  def find_session_dir(start: JFile): Option[isabelle.Path] = {
    var roots_dir: Option[JFile] = None
    var root_dir: Option[JFile] = None
    var d = start

    while (d != null) {
      if (roots_dir.isEmpty && new JFile(d, "ROOTS").exists()) roots_dir = Some(d)
      if (root_dir.isEmpty && new JFile(d, "ROOT").exists()) root_dir = Some(d)
      d = d.getParentFile
    }

    roots_dir.orElse(root_dir).map(f => isabelle.Path.explode(f.getPath))
  }

  /* Project resolution */

  case class ProjectInfo(
      project_session: String,
      parent: String,
      theory_short_names: List[String],
      master_dir: String,
      extra_dirs: List[isabelle.Path]
  )
  def resolve_project(current_file: String): ProjectInfo = {
    val options = PIDE.options.value
    val dir = new JFile(current_file).getParent
    val extra_dirs = find_session_dir(new JFile(dir)).toList
    val sessions =
      JEdit_Session.sessions_structure(dirs = JEdit_Session.session_dirs ::: extra_dirs)
    val project_session = sessions.session_directories
      .get(new JFile(dir).getCanonicalFile)
      .getOrElse(JEdit_Session.logic_name(options))
    val info = sessions(project_session)
    val parent = info.parent.getOrElse(JEdit_Session.logic_name(options))
    val theory_short_names = info.theories.flatMap(_._2.map(_._1))
    val master_dir = info.dir.implode
    ProjectInfo(project_session, parent, theory_short_names, master_dir, extra_dirs)
  }

}
