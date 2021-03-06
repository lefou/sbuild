package org.sbuild

import java.io.File

import org.sbuild.internal.WithinTargetExecution

object TargetRefs extends TargetRefsImplicits {

  def apply(targetRefs: TargetRefs): TargetRefs = targetRefs
  def apply(targetRefs: TargetRef*): TargetRefs = new TargetRefs(Seq(targetRefs))

  implicit def fromString(string: String)(implicit project: Project): TargetRefs = TargetRefs.toTargetRefs_fromString(string)
  implicit def fromFile(file: File)(implicit project: Project): TargetRefs = TargetRefs.toTargetRefs_fromFile(file)
  implicit def fromTargetRef(targetRef: TargetRef): TargetRefs = TargetRefs.toTargetRefs_fromTargetRef(targetRef)
  implicit def fromTarget(target: Target): TargetRefs = TargetRefs.toTargetRefs_fromTarget(target)
  implicit def fromSeq(targetRefs: Seq[TargetRef]): TargetRefs = TargetRefs.toTargetRefs_fromSeq(targetRefs)

}

class TargetRefs private (val targetRefGroups: Seq[Seq[TargetRef]]) {

  lazy val targetRefs = targetRefGroups.flatten

  private[this] def isMulti: Boolean = targetRefGroups.size > 1

  private[this] def closedGroups: Seq[Seq[TargetRef]] = targetRefGroups.size match {
    case 1 => Seq()
    case n => targetRefGroups.take(n - 1)
  }

  private[this] def openGroup: Seq[TargetRef] = targetRefGroups.lastOption match {
    case Some(last) => last
    case None => Seq()
  }

  def ~(targetRefs: TargetRefs): TargetRefs =
    new TargetRefs((
      closedGroups ++
      Seq((openGroup ++ targetRefs.targetRefGroups.head).distinct) ++
      targetRefs.targetRefGroups.tail
    ).filter(!_.isEmpty))
  def ~(targetRef: TargetRef): TargetRefs = this.~(TargetRefs.fromTargetRef(targetRef))
  def ~(file: File)(implicit project: Project): TargetRefs = this.~(TargetRefs.fromFile(file))
  def ~(string: String)(implicit project: Project): TargetRefs = this.~(TargetRefs.fromString(string))
  def ~(target: Target): TargetRefs = this.~(TargetRefs.fromTarget(target))

  // since SBuild 0.5.0.9004
  def ~~(targetRefs: TargetRefs): TargetRefs =
    new TargetRefs((
      targetRefGroups ++
      Seq(targetRefs.targetRefGroups.head) ++
      targetRefs.targetRefGroups.tail
    ).filter(!_.isEmpty))
  def ~~(targetRef: TargetRef): TargetRefs = ~~(TargetRefs.fromTargetRef(targetRef))
  def ~~(file: File)(implicit project: Project): TargetRefs = ~~(TargetRefs.fromFile(file))
  def ~~(string: String)(implicit project: Project): TargetRefs = ~~(TargetRefs.fromString(string))
  def ~~(target: Target): TargetRefs = ~~(TargetRefs.fromTarget(target))

  override def toString: String = targetRefGroups.map { _.mkString(" ~ ") }.mkString(" ~~ ")

  /**
   * Get the files, this TargetRefs is referencing or producing, if any.
   */
  def files: Seq[File] = WithinTargetExecution.safeWithinTargetExecution("TargetRefs.files") {
    withinTargetExec =>
      targetRefs.flatMap(tr => tr.files)
  }

}

trait TargetRefsImplicits {
  implicit def toTargetRefs_fromString(string: String)(implicit project: Project): TargetRefs = TargetRefs(TargetRef(string))
  implicit def toTargetRefs_fromFile(file: File)(implicit project: Project): TargetRefs = TargetRefs(TargetRef(file))
  implicit def toTargetRefs_fromTargetRef(targetRef: TargetRef): TargetRefs = TargetRefs(targetRef)
  implicit def toTargetRefs_fromTarget(target: Target): TargetRefs = TargetRefs(TargetRef(target.name)(target.project))
  implicit def toTargetRefs_fromSeq(targetRefs: Seq[TargetRef]): TargetRefs = TargetRefs(targetRefs: _*)
}
