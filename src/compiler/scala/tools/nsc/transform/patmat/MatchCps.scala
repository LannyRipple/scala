/*
 * Scala (https://www.scala-lang.org)
 *
 * Copyright EPFL and Lightbend, Inc. dba Akka
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package scala.tools.nsc.transform.patmat

/** Segregating this super hacky CPS code. */
trait MatchCps {
  self: PatternMatching =>

  import global._

  // duplicated from CPSUtils (avoid dependency from compiler -> cps plugin...)
  private object CpsSymbols {
    private def cpsSymbol(name: String) = rootMirror.getClassIfDefined(s"scala.util.continuations.$name")

    val MarkerCPSAdaptPlus  = cpsSymbol("cpsPlus")
    val MarkerCPSAdaptMinus = cpsSymbol("cpsMinus")
    val MarkerCPSSynth      = cpsSymbol("cpsSynth")
    val MarkerCPSTypes      = cpsSymbol("cpsParam")
    val stripTriggerCPSAnns = Set[Symbol](MarkerCPSSynth, MarkerCPSAdaptMinus, MarkerCPSAdaptPlus)
    val strippedCPSAnns     = stripTriggerCPSAnns + MarkerCPSTypes

    // when one of the internal cps-type-state annotations is present, strip all CPS annotations
    // a cps-type-state-annotated type makes no sense as an expected type (matchX.tpe is used as pt in translateMatch)
    // (only test availability of MarkerCPSAdaptPlus assuming they are either all available or none of them are)
    def removeCPSFromPt(pt: Type): Type = (
      if (MarkerCPSAdaptPlus.exists && (stripTriggerCPSAnns exists pt.hasAnnotation))
        pt filterAnnotations (ann => !(strippedCPSAnns exists ann.matches))
      else
        pt
    )
  }
  def removeCPSFromPt(pt: Type): Type = CpsSymbols removeCPSFromPt pt
}
