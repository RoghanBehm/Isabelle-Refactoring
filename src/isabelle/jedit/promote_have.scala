package isabelle.jedit

import isabelle.{Document, GUI_Thread, Command, Text}
import org.gjt.sp.jedit.View
import javax.swing.JOptionPane

object Promote_Have {

  case class HaveBlock(
    command: Command,
    range: Text.Range,
    source: String,
    label: Option[String]
  )

  sealed trait LiftCheck
  case object Ok extends LiftCheck
  case class Rejected(reason: String) extends LiftCheck
 
  def apply(view: View): Unit = {
    GUI_Thread.now {
      val text_area = view.getTextArea()
      val doc_view  = Document_View.get(text_area).get
      val snapshot  = Document_View.rendering(doc_view).snapshot
      val caret     = text_area.getCaretPosition

      snapshot.node.command_iterator(caret).nextOption() match {
        case Some((cmd, cmd_start)) if cmd.span.name == "have" =>
          have_block_range(snapshot.node, cmd_start) match {
            case Some(range) => run_promote(view, snapshot, cmd, range)
            case None        =>
              JOptionPane.showMessageDialog(
                view,
                "Could not determine the range of the have block.",
                "Promote_Have",
                JOptionPane.WARNING_MESSAGE
              )
          }
        case _ =>
          JOptionPane.showMessageDialog(
            view,
            "No have command found under caret.",
            "Promote_Have",
            JOptionPane.INFORMATION_MESSAGE
          )
      }
    }
  }

  def run_promote(view: View, snapshot: Document.Snapshot, cmd: Command, range: Text.Range) = {
    view.getBuffer.remove(range.start, range.length)
  }

  def global_write(view: View, snapshot: Document.Snapshot, text: String): Unit = {
    val w_loc = MarkupUtils.offset_after_begin(snapshot).get
    view.getBuffer.insert(w_loc, text)
  }

  def have_block_range(node: Document.Node, have_start: Text.Offset): Option[Text.Range] = {
    val it = node.command_iterator(have_start).filter(_._1.is_proper)
    if (!it.hasNext) return None
    it.next()  // consume the have
    var current: Option[(Command, Int)] = if (it.hasNext) Some(it.next()) else None
    while (current.exists(p => is_proof_modifier(p._1)) && it.hasNext) {
      current = Some(it.next())
    }
    current match {
      case None => None
      case Some((first_proof_cmd, first_start)) =>
        if (is_terminal_method(first_proof_cmd)) {
          Some(Text.Range(have_start, first_start + first_proof_cmd.length))
        } else if (is_proof_open(first_proof_cmd)) {
          var depth = 1
          var last_end = first_start + first_proof_cmd.length
          while (depth > 0 && it.hasNext) {
            val (c, s) = it.next()
            if (is_proof_open(c)) depth += 1
            else if (is_proof_close(c)) depth -= 1
            last_end = s + c.length
          }
          if (depth == 0) Some(Text.Range(have_start, last_end)) else None
        } else {
          None
        }
    }
  }

  /* Predicates */
  def is_proof_open(cmd: Command): Boolean = cmd.span.kind.keyword_kind.contains("prf_block")
  def is_proof_close(cmd: Command): Boolean = cmd.span.kind.keyword_kind.contains("qed_block")
  def is_terminal_method(cmd: Command): Boolean = cmd.span.kind.keyword_kind.contains("qed")
  def is_proof_modifier(cmd: Command): Boolean = {
    cmd.span.kind.keyword_kind.exists(k => k == "prf_decl" || k == "prf_chain")
  }

}
