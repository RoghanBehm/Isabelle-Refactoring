package isabelle.jedit

import isabelle.{Text, Path, File, Document}

sealed trait Edit {
  def range: Text.Range
  def new_text: String
}

object Edit {
  case class Replace(range: Text.Range, new_text: String) extends Edit
  case class Insert(offset: Int, new_text: String) extends Edit {
    def range: Text.Range = Text.Range(offset, offset)
  }
  case class Delete(range: Text.Range) extends Edit {
    def new_text: String = ""
  }

  /** Apply a list of edits to a single file's text. Edits must not overlap. */
  def apply_to_text(text: String, edits: List[Edit]): String = {
    val sorted = edits.sortBy(-_.range.start)
    check_no_overlap(sorted)
    sorted.foldLeft(text) { (t, e) =>
      t.substring(0, e.range.start) + e.new_text + t.substring(e.range.stop)
    }
  }

  /** Group by node, apply, write. Returns the set of nodes touched. */
  def apply_to_files(
    edits: List[(Document.Node.Name, Edit)]
  ): List[Document.Node.Name] = {
    val grouped = edits.groupMap(_._1)(_._2)
    grouped.foreach { case (node, es) =>
      val path = Path.explode(node.node)
      val text = File.read(path)
      File.write(path, apply_to_text(text, es))
    }
    grouped.keys.toList
  }


  private def check_no_overlap(sorted_desc: List[Edit]): Unit = {
    sorted_desc.sliding(2).foreach {
      case List(a, b) if b.range.stop > a.range.start =>
        throw new IllegalArgumentException(s"Overlapping edits: $a vs $b")
      case _ =>
    }
  }
}
