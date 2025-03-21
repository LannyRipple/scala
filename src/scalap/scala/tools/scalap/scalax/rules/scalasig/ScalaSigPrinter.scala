/*
 * Scala classfile decoder (https://www.scala-lang.org)
 *
 * Copyright EPFL and Lightbend, Inc. dba Akka
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package scala.tools.scalap
package scalax
package rules
package scalasig

import java.io.{PrintStream, ByteArrayOutputStream}
import java.util.regex.Pattern
import scala.reflect.NameTransformer


class ScalaSigPrinter(stream: PrintStream, printPrivates: Boolean) {
  import stream._

  val CONSTRUCTOR_NAME = "<init>"

  case class TypeFlags(printRep: Boolean)

  def printSymbol(symbol: Symbol): Unit = {printSymbol(0, symbol)}

  def printSymbolAttributes(s: Symbol, onNewLine: Boolean, indent: => Unit) = s match {
    case t: SymbolInfoSymbol => {
      for (a <- t.attributes) {
        indent; print(toString(a))
        if (onNewLine) print("\n") else print(" ")
      }
    }
    case _ =>
  }

  def printSymbol(level: Int, symbol: Symbol): Unit = {
    if (!symbol.isLocal &&
            !(symbol.isPrivate && !printPrivates)) {
      def indent(): Unit = {for (i <- 1 to level) print("  ")}

      printSymbolAttributes(symbol, onNewLine = true, indent())
      symbol match {
        case o: ObjectSymbol =>
          if (!isCaseClassObject(o)) {
            indent()
            if (o.name == "package") {
              // print package object
              printPackageObject(level, o)
            } else {
              printObject(level, o)
            }
          }
        case c: ClassSymbol if !refinementClass(c) && !c.isModule =>
          indent()
          printClass(level, c)
        case m: MethodSymbol =>
          printMethod(level, m, () => indent())
        case a: AliasSymbol =>
          indent()
          printAlias(level, a)
        case t: TypeSymbol if !t.name.matches("_\\$\\d+")=>
          indent()
          printTypeSymbol(level, t)
        case s =>
      }
    }
  }

  def isCaseClassObject(o: ObjectSymbol): Boolean = {
    val TypeRefType(_, classSymbol: ClassSymbol, _) = o.infoType: @unchecked
    o.isFinal && (classSymbol.children.find(x => x.isCase && x.isInstanceOf[MethodSymbol]) match {
      case Some(_) => true
      case None => false
    })
  }

  private def underCaseClass(m: MethodSymbol) = m.parent match {
    case Some(c: ClassSymbol) => c.isCase
    case _ => false
  }


  private def printChildren(level: Int, symbol: Symbol): Unit = {
    for (child <- symbol.children) printSymbol(level + 1, child)
  }

  def printWithIndent(level: Int, s: String): Unit = {
    def indent(): Unit = {for (i <- 1 to level) print("  ")}
    indent()
    print(s)
  }

  def printModifiers(symbol: Symbol): Unit = {
    // print private access modifier
    if (symbol.isPrivate) print("private ")
    else if (symbol.isProtected) print("protected ")
    else symbol match {
      case sym: SymbolInfoSymbol => sym.symbolInfo.privateWithin match {
        case Some(t: Symbol) => print("private[" + t.name +"] ")
        case _ =>
      }
      case _ =>
    }

    if (symbol.isSealed) print("sealed ")
    if (symbol.isImplicit) print("implicit ")
    if (symbol.isFinal && !symbol.isInstanceOf[ObjectSymbol]) print("final ")
    if (symbol.isOverride) print("override ")
    if (symbol.isAbstract) symbol match {
      case c@(_: ClassSymbol | _: ObjectSymbol) if !c.isTrait => print("abstract ")
      case _ => ()
    }
    if (symbol.isCase && !symbol.isMethod) print("case ")
  }

  private def refinementClass(c: ClassSymbol) = c.name == "<refinement>"

  def printClass(level: Int, c: ClassSymbol): Unit = {
    if (c.name == "<local child>" /*scala.tools.nsc.symtab.StdNames.LOCAL_CHILD.toString()*/ ) {
      print("\n")
    } else {
      printModifiers(c)
      val defaultConstructor = if (c.isCase) getPrinterByConstructor(c) else ""
      if (c.isTrait) print("trait ") else print("class ")
      print(processName(c.name))
      val it = c.infoType
      val classType = it match {
        case PolyType(typeRef, symbols) => PolyTypeWithCons(typeRef, symbols, defaultConstructor)
        case ClassInfoType(a, b) if c.isCase => ClassInfoTypeWithCons(a, b, defaultConstructor)
        case _ => it
      }
      printType(classType)
      print(" {")
      //Print class selftype
      c.selfType match {
        case Some(t: Type) => print("\n"); print(" this: " + toString(t) + " =>")
        case None =>
      }
      print("\n")
      printChildren(level, c)
      printWithIndent(level, "}\n")
    }
  }

  def getPrinterByConstructor(c: ClassSymbol) = {
    c.children.find {
      case m: MethodSymbol if m.name == CONSTRUCTOR_NAME => true
      case _ => false
    } match {
      case Some(m: MethodSymbol) =>
        val baos = new ByteArrayOutputStream
        val stream = new PrintStream(baos)
        val printer = new ScalaSigPrinter(stream, printPrivates)
        printer.printMethodType(m.infoType, printResult = false)(())
        baos.toString
      case _ =>
        ""
    }
  }

  def printPackageObject(level: Int, o: ObjectSymbol): Unit = {
    printModifiers(o)
    print("package ")
    print("object ")
    val poName = o.symbolInfo.owner.name
    print(processName(poName))
    val TypeRefType(_, classSymbol: ClassSymbol, _) = o.infoType: @unchecked
    printType(classSymbol)
    print(" {\n")
    printChildren(level, classSymbol)
    printWithIndent(level, "}\n")

  }

  def printObject(level: Int, o: ObjectSymbol): Unit = {
    printModifiers(o)
    print("object ")
    print(processName(o.name))
    val TypeRefType(_, classSymbol: ClassSymbol, _) = o.infoType: @unchecked
    printType(classSymbol)
    print(" {\n")
    printChildren(level, classSymbol)
    printWithIndent(level, "}\n")
  }

  def printMethodType(t: Type, printResult: Boolean)(cont: => Unit): Unit = {

    def _pmt(mt: MethodType) = {

      val paramEntries = mt.paramSymbols.map({
        case ms: MethodSymbol => ms.name + ": " + toString(ms.infoType)(TypeFlags(printRep = true))
        case _ => "^___^"
      })
      val implicitWord = mt.paramSymbols.headOption match {
        case Some(p) if p.isImplicit  => "implicit "
        case _                        => ""
      }

      // Print parameter clauses
      print(paramEntries.mkString("(" + implicitWord, ", ", ")"))

      // Print result type
      mt.resultType match {
        case res: MethodType => printMethodType(res, printResult)(())
        case x => if (printResult) {
          print(": ")
          printType(x)
        }
      }
    }

    t match {
      case NullaryMethodType(resType) => if (printResult) { print(": "); printType(resType) }
      case mt@MethodType(resType, paramSymbols) => _pmt(mt)
      case pt@PolyType(mt, typeParams) => {
        print(typeParamString(typeParams))
        printMethodType(mt, printResult)({})
      }
      //todo consider another method types
      case x => print(": "); printType(x)
    }

    // Print rest of the symbol output
    cont
  }

  def printMethod(level: Int, m: MethodSymbol, indent: () => Unit): Unit = {
    def cont() = print(" = { /* compiled code */ }")

    val n = m.name
    if (underCaseClass(m) && n == CONSTRUCTOR_NAME) return
    if (n.matches(".+\\$default\\$\\d+")) return // skip default function parameters
    if (n.startsWith("super$")) return // do not print auxiliary qualified super accessors
    if (m.isAccessor && n.endsWith("_$eq")) return
    indent()
    printModifiers(m)
    if (m.isAccessor) {
      val indexOfSetter = m.parent.get.children.indexWhere(x => x.isInstanceOf[MethodSymbol] &&
              x.asInstanceOf[MethodSymbol].name == n + "_$eq")
      print(if (indexOfSetter > 0) "var " else "val ")
    } else {
      print("def ")
    }
    n match {
      case CONSTRUCTOR_NAME =>
        print("this")
        printMethodType(m.infoType, printResult = false)(cont())
      case name =>
        val nn = processName(name)
        print(nn)
        printMethodType(m.infoType, printResult = true)(
          {if (!m.isDeferred) print(" = { /* compiled code */ }" /* Print body only for non-abstract methods */ )}
          )
    }
    print("\n")
  }

  def printAlias(level: Int, a: AliasSymbol): Unit = {
    print("type ")
    print(processName(a.name))
    printType(a.infoType, " = ")
    print("\n")
    printChildren(level, a)
  }

  def printTypeSymbol(level: Int, t: TypeSymbol): Unit = {
    print("type ")
    print(processName(t.name))
    printType(t.infoType)
    print("\n")
  }

  def toString(attrib: AttributeInfo): String  = {
    val buffer = new StringBuffer
    buffer.append(toString(attrib.typeRef, "@"))
    if (attrib.value.isDefined) {
      buffer.append("(")
      val value = attrib.value.get
      val stringVal = value.isInstanceOf[String]
      if (stringVal) buffer.append("\"")
      val stringValue = valueToString(value)
      val isMultiline = stringVal && (stringValue.contains("\n")
              || stringValue.contains("\r"))
      if (isMultiline) buffer.append("\"\"")
      buffer.append(valueToString(value))
      if (isMultiline) buffer.append("\"\"")
      if (stringVal) buffer.append("\"")
      buffer.append(")")
    }
    if (!attrib.values.isEmpty) {
      buffer.append(" {")
      for (name ~ value <- attrib.values) {
        buffer.append(" val ")
        buffer.append(processName(name))
        buffer.append(" = ")
        buffer.append(valueToString(value))
      }
      buffer.append(valueToString(attrib.value))
      buffer.append(" }")
    }
    buffer.toString
  }

  def valueToString(value: Any): String = value match {
    case t: Type => toString(t)
    // TODO string, char, float, etc.
    case _ => value.toString
  }

  implicit object _tf extends TypeFlags(printRep = false)

  def printType(sym: SymbolInfoSymbol)(implicit flags: TypeFlags): Unit = printType(sym.infoType)(flags)

  def printType(t: Type)(implicit flags: TypeFlags): Unit = print(toString(t)(flags))

  def printType(t: Type, sep: String)(implicit flags: TypeFlags): Unit = print(toString(t, sep)(flags))

  def toString(t: Type)(implicit flags: TypeFlags): String = toString(t, "")(flags)

  def toString(t: Type, sep: String)(implicit flags: TypeFlags): String = {
    // print type itself
    t match {
      case ThisType(symbol)            => sep + processName(symbol.path) + ".type"
      case SingleType(typeRef, symbol) => sep + processName(symbol.path) + ".type"
      case ConstantType(constant)      => sep + (constant match {
        case null              => "scala.Null"
        case _: Unit           => "scala.Unit"
        case _: Boolean        => "scala.Boolean"
        case _: Byte           => "scala.Byte"
        case _: Char           => "scala.Char"
        case _: Short          => "scala.Short"
        case _: Int            => "scala.Int"
        case _: Long           => "scala.Long"
        case _: Float          => "scala.Float"
        case _: Double         => "scala.Double"
        case _: String         => "java.lang.String"
        case c: Class[_]       => "java.lang.Class[" + c.getComponentType.getCanonicalName.replace("$", ".") + "]"
        case e: ExternalSymbol => e.parent.get.path
        case tp: Type          => "java.lang.Class[" + toString(tp, sep) + "]"
        case x                 => throw new MatchError(x)
      })
      case TypeRefType(prefix, symbol, typeArgs) => sep + (symbol.path match {
        case "scala.<repeated>" => flags match {
          case TypeFlags(true) => toString(typeArgs.head) + "*"
          case _               => "scala.Seq" + typeArgString(typeArgs)
        }
        case "scala.<byname>" => "=> " + toString(typeArgs.head)
        case _ => {
          val path = symbol.path.replace(".package", "") //remove package object reference
          (processName(path) + typeArgString(typeArgs)).stripPrefix("<empty>.")
        }
      })
      case TypeBoundsType(lower, upper) => {
        val lb = toString(lower)
        val ub = toString(upper)
        val lbs = if (!lb.equals("scala.Nothing")) " >: " + lb else ""
        val ubs = if (!ub.equals("scala.Any")) " <: " + ub else ""
        lbs + ubs
      }
      case RefinedType(classSym, typeRefs) => sep + typeRefs.map(toString).mkString("", " with ", "")
      case ClassInfoType(symbol, typeRefs) => sep + typeRefs.map(toString).mkString(" extends ", " with ", "")
      case ClassInfoTypeWithCons(symbol, typeRefs, cons) => sep + typeRefs.map(toString).
              mkString(cons + " extends ", " with ", "")

      case MethodType(resultType, _) => toString(resultType, sep)
      case NullaryMethodType(resultType) => toString(resultType, sep)

      case PolyType(typeRef, symbols) => typeParamString(symbols) + toString(typeRef, sep)
      case PolyTypeWithCons(typeRef, symbols, cons) => typeParamString(symbols) + processName(cons) + toString(typeRef, sep)
      case AnnotatedType(typeRef, attribTreeRefs) => {
        toString(typeRef, sep)
      }
      case AnnotatedWithSelfType(typeRef, symbol, attribTreeRefs) => toString(typeRef, sep)
      case ExistentialType(typeRef, symbols) => {
        val refs = symbols.map(toString).filter(!_.startsWith("_")).map("type " + _)
        toString(typeRef, sep) + (if (refs.size > 0) refs.mkString(" forSome {", "; ", "}") else "")
      }
      case _ => sep + t.toString
    }
  }

  def getVariance(t: TypeSymbol) = if (t.isCovariant) "+" else if (t.isContravariant) "-" else ""

  def toString(symbol: Symbol): String = symbol match {
    case symbol: TypeSymbol => {
      val attrs = (for (a <- symbol.attributes) yield toString(a)).mkString(" ")
      val atrs = if (attrs.length > 0) attrs.trim + " " else ""
      atrs + getVariance(symbol) + processName(symbol.name) + toString(symbol.infoType)
    }
    case s => symbol.toString
  }

  def typeArgString(typeArgs: Seq[Type]): String =
    if (typeArgs.isEmpty) ""
    else typeArgs.map(toString).map(_.stripPrefix("=> ")).mkString("[", ", ", "]")

  def typeParamString(params: Seq[Symbol]): String =
    if (params.isEmpty) ""
    else params.map(toString).mkString("[", ", ", "]")

  val _syms = Map("\\$bar" -> "|", "\\$tilde" -> "~",
    "\\$bang" -> "!", "\\$up" -> "^", "\\$plus" -> "+",
    "\\$minus" -> "-", "\\$eq" -> "=", "\\$less" -> "<",
    "\\$times" -> "*", "\\$div" -> "/", "\\$bslash" -> "\\\\",
    "\\$greater" -> ">", "\\$qmark" -> "?", "\\$percent" -> "%",
    "\\$amp" -> "&", "\\$colon" -> ":", "\\$u2192" -> "→",
    "\\$hash" -> "#")
  val pattern = Pattern.compile(_syms.keys.foldLeft("")((x, y) => if (x == "") y else x + "|" + y))
  val placeholderPattern = "_\\$(\\d)+"

  private def stripPrivatePrefix(name: String) = {
    val i = name.lastIndexOf("$$")
    if (i > 0) name.substring(i + 2) else name
  }

  def processName(name: String) = {
    val stripped = stripPrivatePrefix(name)
    val m = pattern.matcher(stripped)
    var temp = stripped
    while (m.find) {
      val key = m.group
      val re = "\\" + key
      temp = temp.replaceAll(re, _syms(re))
    }
    val result = temp.replaceAll(placeholderPattern, "_")
    NameTransformer.decode(result)
  }

}
