package isabelle.jedit

import org.gjt.sp.jedit.View

/* This file is the entry-point for refactoring actions */


object Refactor {
  def rename(view: View): Unit = Rename(view)
}
