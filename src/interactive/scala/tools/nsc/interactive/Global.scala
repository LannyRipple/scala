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

package scala.tools.nsc
package interactive

import java.io.{FileReader, FileWriter}
import java.util.concurrent.ConcurrentHashMap

import scala.annotation.{elidable, nowarn, tailrec}
import scala.collection.mutable
import scala.collection.mutable.{HashSet, LinkedHashMap}
import scala.jdk.javaapi.CollectionConverters
import scala.language.implicitConversions
import scala.reflect.internal.util.SourceFile
import scala.tools.nsc.io.AbstractFile
import scala.tools.nsc.reporters.Reporter
import scala.tools.nsc.symtab.Flags.{ACCESSOR, PARAMACCESSOR}
import scala.tools.nsc.symtab._
import scala.tools.nsc.typechecker.{Analyzer, Typers}
import scala.util.control.Breaks._
import scala.util.control.ControlThrowable

/**
 * This trait allows the IDE to have an instance of the PC that
 * does not clear the comments table at every new typer run (those
 * being many and close between in this context).
 */

trait CommentPreservingTypers extends Typers {
  self: Analyzer =>

  override def resetDocComments() = {}
}

trait InteractiveAnalyzer extends Analyzer {
  val global : Global
  import global._

  override def newTyper(context: Context): InteractiveTyper = new Typer(context) with InteractiveTyper
  override def newNamer(context: Context): InteractiveNamer = new Namer(context) with InteractiveNamer

  trait InteractiveTyper extends Typer {
    override def isInteractive = true
    override def canAdaptConstantTypeToLiteral = false
    override def canTranslateEmptyListToNil    = false
    override def missingSelectErrorTree(tree: Tree, qual: Tree, name: Name): Tree = tree match {
      case Select(_, _)             => treeCopy.Select(tree, qual, name)
      case SelectFromTypeTree(_, _) => treeCopy.SelectFromTypeTree(tree, qual, name)
      case _                        => tree
    }
  }

  trait InteractiveNamer extends Namer {
    override def saveDefaultGetter(meth: Symbol, default: Symbol): Unit = {
      // save the default getters as attachments in the method symbol. if compiling the
      // same local block several times (which can happen in interactive mode) we might
      // otherwise not find the default symbol, because the second time it the method
      // symbol will be re-entered in the scope but the default parameter will not.
      meth.attachments.get[DefaultsOfLocalMethodAttachment] match {
        case Some(att) => att.defaultGetters += default
        case None      => meth.updateAttachment(new DefaultsOfLocalMethodAttachment(default))
      }
    }
    // this logic is needed in case typer was interrupted half
    // way through and then comes back to do the tree again. In
    // that case the definitions that were already attributed as
    // well as any default parameters of such methods need to be
    // re-entered in the current scope.
    //
    // Tested in test/files/presentation/t8941b
    override def enterExistingSym(sym: Symbol, tree: Tree): Context = {
      if (sym != null && sym.owner.isTerm) {
        enterIfNotThere(sym)

        for (defAtt <- sym.attachments.get[DefaultsOfLocalMethodAttachment])
          defAtt.defaultGetters foreach enterIfNotThere
      } else if (sym != null && sym.isClass && sym.isImplicit) {
        val owningInfo = sym.owner.info
        val existingDerivedSym = owningInfo.decl(sym.name.toTermName).filter(sym => sym.isSynthetic && sym.isMethod)
        existingDerivedSym.alternatives foreach (owningInfo.decls.unlink)
        val defTree = tree match {
          case dd: DocDef => dd.definition // See scala/bug#9011, Scala IDE's presentation compiler incorporates ScaladocGlobal with InteractiveGlobal, so we have to unwrap DocDefs.
          case _ => tree
        }
        enterImplicitWrapper(defTree.asInstanceOf[ClassDef])
      }
      super.enterExistingSym(sym, tree)
    }
    override def enterIfNotThere(sym: Symbol): Unit = {
      val scope = context.scope
      @tailrec def search(e: ScopeEntry): Unit = {
        if ((e eq null) || (e.owner ne scope))
          scope enter sym
        else if (e.sym ne sym)  // otherwise, aborts since we found sym
          search(e.tail)
      }
      search(scope lookupEntry sym.name)
    }
  }
}

/** The main class of the presentation compiler in an interactive environment such as an IDE
 */
class Global(settings: Settings, _reporter: Reporter, projectName: String = "") extends {
  /* Is the compiler initializing? Early def, so that the field is true during the
   *  execution of the super constructor.
   */
  private var initializing = true
  override val useOffsetPositions = false
} with scala.tools.nsc.Global(settings, _reporter)
  with CompilerControl
  with ContextTrees
  with RichCompilationUnits
  with Picklers {

  import definitions._

  if (!settings.Ymacroexpand.isSetByUser)
    settings.Ymacroexpand.value = settings.MacroExpand.Discard

  val debugIDE: Boolean = settings.YpresentationDebug.value
  val verboseIDE: Boolean = settings.YpresentationVerbose.value
  private val anyThread: Boolean = settings.YpresentationAnyThread.value

  private def replayName = settings.YpresentationReplay.value
  private def logName = settings.YpresentationLog.value
  private def afterTypeDelay = settings.YpresentationDelay.value
  private final val SleepTime = 10

  val log =
    if (replayName != "") new Replayer(new FileReader(replayName))
    else if (logName != "") new Logger(new FileWriter(logName))
    else NullLogger

  import log.logreplay
  debugLog(s"logger: ${log.getClass} writing to ${(new java.io.File(logName)).getAbsolutePath}")
  debugLog(s"classpath: $classPath")

  private var curTime = System.nanoTime
  private def timeStep = {
    val last = curTime
    curTime = System.nanoTime
    ", delay = " + (curTime - last) / 1000000 + "ms"
  }

  /** Print msg only when debugIDE is true. */
  @inline final def debugLog(msg: => String) =
    if (debugIDE) println("[%s] %s".format(projectName, msg))

  /** Inform with msg only when verboseIDE is true. */
  @inline final def informIDE(msg: => String) =
    if (verboseIDE) println("[%s][%s]".format(projectName, msg))

  // don't keep the original owner in presentation compiler runs
  // (the map will grow indefinitely, and the only use case is the backend)
  override def defineOriginalOwner(sym: Symbol, owner: Symbol): Unit = { }

  override protected def synchronizeNames = true

  /** A map of all loaded files to the rich compilation units that correspond to them.
   */
  val unitOfFile: mutable.Map[AbstractFile, RichCompilationUnit] = {
    val m = new ConcurrentHashMap[AbstractFile, RichCompilationUnit] {
      override def put(key: AbstractFile, value: RichCompilationUnit) = {
        val r = super.put(key, value)
        if (r == null) debugLog("added unit for "+key)
        r
      }
      override def remove(key: Any) = {
        val r = super.remove(key)
        if (r != null) debugLog("removed unit for "+key)
        r
      }
    }
    CollectionConverters.asScala(m)
  }

  /** A set containing all those files that need to be removed
   *  Units are removed by getUnit, typically once a unit is finished compiled.
   */
  protected val toBeRemoved: HashSet[AbstractFile] = new HashSet[AbstractFile]

  /** A set containing all those files that need to be removed after a full background compiler run
   */
  protected val toBeRemovedAfterRun: HashSet[AbstractFile] = new HashSet[AbstractFile]

  private def newResponseMap: ResponseMap =
    mutable.HashMap.empty[SourceFile, Set[Response[Tree]]].withDefaultValue(Set.empty[Response[Tree]])
  type ResponseMap = mutable.Map[SourceFile, Set[Response[Tree]]]
  /* TODO restore assert on addOne
      assert(interruptsEnabled, "delayed operation within an ask")
  */

  /** A map that associates with each abstract file the set of responses that are waiting
   *  (via waitLoadedTyped) for the unit associated with the abstract file to be loaded and completely typechecked.
   */
  protected val waitLoadedTypeResponses = newResponseMap

  /** A map that associates with each abstract file the set of responses that ware waiting
   *  (via build) for the unit associated with the abstract file to be parsed and entered
   */
  protected var getParsedEnteredResponses = newResponseMap

  private def cleanResponses(rmap: ResponseMap): Unit = {
    for ((source, rs) <- rmap.toList) {
      for (r <- rs) {
        if (getUnit(source).isEmpty)
          r raise new NoSuchUnitError(source.file)
        if (r.isComplete)
          rmap(source) -= r
      }
      if (rmap(source).isEmpty)
        rmap -= source
    }
  }

  override lazy val analyzer = new {
    val global: Global.this.type = Global.this
  } with InteractiveAnalyzer

  private def cleanAllResponses(): Unit = {
    cleanResponses(waitLoadedTypeResponses)
    cleanResponses(getParsedEnteredResponses)
  }

  private def checkNoOutstanding(rmap: ResponseMap): Unit =
    for ((_, rs) <- rmap.toList; r <- rs) {
      debugLog("ERROR: missing response, request will be discarded")
      r raise new MissingResponse
    }

  def checkNoResponsesOutstanding(): Unit = {
    checkNoOutstanding(waitLoadedTypeResponses)
    checkNoOutstanding(getParsedEnteredResponses)
  }

  /** The compilation unit corresponding to a source file
   *  if it does not yet exist create a new one atomically
   *  Note: We want to remove this.
   */
  protected[interactive] def getOrCreateUnitOf(source: SourceFile): RichCompilationUnit =
    unitOfFile.getOrElse(source.file, { println("precondition violated: "+source+" is not loaded"); new Exception().printStackTrace(); new RichCompilationUnit(source) })

  /** Work through toBeRemoved list to remove any units.
   *  Then return optionally unit associated with given source.
   */
  protected[interactive] def getUnit(s: SourceFile): Option[RichCompilationUnit] = {
    toBeRemoved.synchronized {
      for (f <- toBeRemoved) {
        informIDE("removed: "+s)
        unitOfFile -= f
        allSources = allSources filter (_.file != f)
      }
      toBeRemoved.clear()
    }
    unitOfFile get s.file
  }

  /** A list giving all files to be typechecked in the order they should be checked.
   */
  protected var allSources: List[SourceFile] = List()

  private var lastException: Option[Throwable] = None

  /** A list of files that crashed the compiler. They will be ignored during background
   *  compilation until they are removed from this list.
   */
  private var ignoredFiles: Set[AbstractFile] = Set()

  /** Flush the buffer of sources that are ignored during background compilation. */
  def clearIgnoredFiles(): Unit = {
    ignoredFiles = Set()
  }

  /** Remove a crashed file from the ignore buffer. Background compilation will take it into account
   *  and errors will be reported against it. */
  def enableIgnoredFile(file: AbstractFile): Unit = {
    ignoredFiles -= file
    debugLog("Removed crashed file %s. Still in the ignored buffer: %s".format(file, ignoredFiles))
  }

  /** The currently active typer run */
  private var currentTyperRun: TyperRun = _
  newTyperRun()

  /** Is a background compiler run needed?
   *  Note: outOfDate is true as long as there is a background compile scheduled or going on.
   */
  private var outOfDate = false

  def isOutOfDate: Boolean = outOfDate

  def demandNewCompilerRun() = {
    if (outOfDate) throw new FreshRunReq // cancel background compile
    else outOfDate = true            // proceed normally and enable new background compile
  }

  protected[interactive] var minRunId = 1

  private[interactive] var interruptsEnabled = true

  private val NoResponse: Response[_] = new Response[Any]

  /** The response that is currently pending, i.e. the compiler
   *  is working on providing an answer for it.
   */
  private var pendingResponse: Response[_] = NoResponse

  // ----------- Overriding hooks in nsc.Global -----------------------

  /** Called from parser, which signals hereby that a method definition has been parsed.
   */
  override def signalParseProgress(pos: Position): Unit = {
    // We only want to be interruptible when running on the PC thread.
    if(onCompilerThread) {
      checkForMoreWork(pos)
    }
  }

  /** Called from typechecker, which signals hereby that a node has been completely typechecked.
   *  If the node includes unit.targetPos, abandons run and returns newly attributed tree.
   *  Otherwise, if there's some higher priority work to be done, also abandons run with a FreshRunReq.
   *  @param  context  The context that typechecked the node
   *  @param  old      The original node
   *  @param  result   The transformed node
   */
  override def signalDone(context: Context, old: Tree, result: Tree): Unit = {
    val canObserveTree = (
         interruptsEnabled
      && lockedCount == 0
      && !context.bufferErrors // scala/bug#7558 look away during exploratory typing in "silent mode"
    )
    if (canObserveTree) {
      if (context.unit.exists &&
          result.pos.isOpaqueRange &&
          (result.pos includes context.unit.targetPos)) {
        var located = new TypedLocator(context.unit.targetPos) locateIn result
        if (located == EmptyTree) {
          println("something's wrong: no "+context.unit+" in "+result+result.pos)
          located = result
        }
        throw new TyperResult(located)
      }
      else {
        try {
          checkForMoreWork(old.pos)
        } catch {
          case ex: ValidateException => // Ignore, this will have been reported elsewhere
            debugLog("validate exception caught: "+ex)
          case ex: Throwable =>
            log.flush()
            throw ex
        }
      }
    }
  }

  /** Called from typechecker every time a context is created.
   *  Registers the context in a context tree
   */
  override def registerContext(c: Context) = c.unit match {
    case u: RichCompilationUnit => addContext(u.contexts, c)
    case _ =>
  }

  /** The top level classes and objects currently seen in the presentation compiler
   */
  private val currentTopLevelSyms = new mutable.LinkedHashSet[Symbol]

  /** The top level classes and objects no longer seen in the presentation compiler
   */
  val deletedTopLevelSyms = new mutable.LinkedHashSet[Symbol]

  /** Called from typechecker every time a top-level class or object is entered.
   */
  override def registerTopLevelSym(sym: Symbol): Unit = { currentTopLevelSyms += sym }

  protected type SymbolLoadersInInteractive = GlobalSymbolLoaders {
    val global: Global.this.type
    val platform: Global.this.platform.type
  }
  /** Symbol loaders in the IDE parse all source files loaded from a package for
   *  top-level idents. Therefore, we can detect top-level symbols that have a name
   *  different from their source file
   */
  override lazy val loaders: SymbolLoadersInInteractive = new {
    val global: Global.this.type = Global.this
    val platform: Global.this.platform.type = Global.this.platform
  } with BrowsingLoaders

  override def openPackageModule(pkgClass: Symbol, force: Boolean): Unit = {
    val isPastNamer = force || currentTyperRun == null || (currentTyperRun.currentUnit match {
      case unit: RichCompilationUnit => unit.isParsed
      case _                         => true
    })
    if (isPastNamer) super.openPackageModule(pkgClass, force = true)
    else analyzer.packageObjects.deferredOpen.add(pkgClass)
  }

  // ----------------- Polling ---------------------------------------

  case class WorkEvent(atNode: Int, atMillis: Long)

  private var moreWorkAtNode: Int = -1
  private var nodesSeen = 0
  private var lastWasReload = false

  /** The number of pollForWorks after which the presentation compiler yields.
   *  Yielding improves responsiveness on systems with few cores because it
   *  gives the UI thread a chance to get new tasks and interrupt the presentation
   *  compiler with them.
   */
  private final val yieldPeriod = 10

  /** Called from runner thread and signalDone:
   *  Poll for interrupts and execute them immediately.
   *  Then, poll for exceptions and execute them.
   *  Then, poll for work reload/typedTreeAt/doFirst commands during background checking.
   *  @param pos   The position of the tree if polling while typechecking, NoPosition otherwise
   *
   */
  private[interactive] def pollForWork(pos: Position): Unit = {
    var loop: Boolean = true
    while (loop) {
      breakable{
        loop = false
        // TODO refactor to eliminate breakable/break/return?
        (if (!interruptsEnabled) return): @nowarn("cat=lint-nonlocal-return")
        if (pos == NoPosition || nodesSeen % yieldPeriod == 0)
          Thread.`yield`()

        def nodeWithWork(): Option[WorkEvent] =
          if (scheduler.moreWork || pendingResponse.isCancelled) Some(new WorkEvent(nodesSeen, System.currentTimeMillis))
          else None

        nodesSeen += 1
        logreplay("atnode", nodeWithWork()) match {
          case Some(WorkEvent(id, _)) =>
            debugLog("some work at node "+id+" current = "+nodesSeen)
          //        assert(id >= nodesSeen)
          moreWorkAtNode = id
          case None =>
        }

        if (nodesSeen >= moreWorkAtNode) {

          logreplay("asked", scheduler.pollInterrupt()) match {
            case Some(ir) =>
              try {
                interruptsEnabled = false
                debugLog("ask started"+timeStep)
                ir.execute()
              } finally {
                debugLog("ask finished"+timeStep)
                interruptsEnabled = true
              }
            loop = true; break()
            case _ =>
          }

          if (logreplay("cancelled", pendingResponse.isCancelled)) {
            throw CancelException
          }

          logreplay("exception thrown", scheduler.pollThrowable()) match {
            case Some(ex: FreshRunReq) =>
              newTyperRun()
            minRunId = currentRunId
            demandNewCompilerRun()

            case Some(ShutdownReq) =>
              scheduler.synchronized { // lock the work queue so no more items are posted while we clean it up
                val units = scheduler.dequeueAll {
                  case item: WorkItem => Some(item.raiseMissing())
                  case _ => Some(())
                }

                // don't forget to service interrupt requests
                scheduler.dequeueAllInterrupts(_.execute())

                debugLog("ShutdownReq: cleaning work queue (%d items)".format(units.size))
                debugLog("Cleanup up responses (%d loadedType pending, %d parsedEntered pending)"
                         .format(waitLoadedTypeResponses.size, getParsedEnteredResponses.size))
                checkNoResponsesOutstanding()

                log.flush()
                scheduler = new NoWorkScheduler
                throw ShutdownReq
              }

            case Some(ex: Throwable) => log.flush(); throw ex
            case _ =>
          }

          lastWasReload = false

          logreplay("workitem", scheduler.nextWorkItem()) match {
            case Some(action) =>
              try {
                debugLog("picked up work item at "+pos+": "+action+timeStep)
                action()
                debugLog("done with work item: "+action)
              } finally {
                debugLog("quitting work item: "+action+timeStep)
              }
            case None =>
          }
        }
      }
    }
  }

  protected def checkForMoreWork(pos: Position): Unit = {
    val typerRun = currentTyperRun
    pollForWork(pos)
    if (typerRun != currentTyperRun) demandNewCompilerRun()
  }

  // ----------------- The Background Runner Thread -----------------------

  private var threadId = 0

  /** The current presentation compiler runner */
  @volatile private[interactive] var compileRunner: Thread = newRunnerThread()

  /** Check that the currently executing thread is the presentation compiler thread.
   *
   *  Compiler initialization may happen on a different thread (signalled by globalPhase being NoPhase)
   */
  @elidable(elidable.WARNING)
  override def assertCorrectThread(): Unit = {
    assert(initializing || anyThread || onCompilerThread,
        "Race condition detected: You are running a presentation compiler method outside the PC thread.[phase: %s]".format(globalPhase) +
        " Please file a ticket with the current stack trace at https://www.assembla.com/spaces/scala-ide/support/tickets")
  }

  /** Create a new presentation compiler runner.
   */
  private def newRunnerThread(): Thread = {
    threadId += 1
    compileRunner = new PresentationCompilerThread(this, projectName)
    compileRunner.setDaemon(true)
    compileRunner
  }

  private def ensureUpToDate(unit: RichCompilationUnit) =
    if (!unit.isUpToDate && unit.status != JustParsed) reset(unit) // reparse previously typechecked units.

  /** Compile all loaded source files in the order given by `allSources`.
   */
  private[interactive] final def backgroundCompile(): Unit = {
    informIDE("Starting new presentation compiler type checking pass")
    reporter.reset()

    // remove any files in first that are no longer maintained by presentation compiler (i.e. closed)
    allSources = allSources filter (s => unitOfFile contains (s.file))

    // ensure all loaded units are parsed
    for (s <- allSources; unit <- getUnit(s)) {
      // checkForMoreWork(NoPosition)  // disabled, as any work done here would be in an inconsistent state
      ensureUpToDate(unit)
      parseAndEnter(unit)
      serviceParsedEntered()
    }

    // sleep window
    if (afterTypeDelay > 0 && lastWasReload) {
      val limit = System.currentTimeMillis() + afterTypeDelay
      while (System.currentTimeMillis() < limit) {
        Thread.sleep(SleepTime)
        checkForMoreWork(NoPosition)
      }
    }

    // ensure all loaded units are typechecked
    for (s <- allSources; if !ignoredFiles(s.file); unit <- getUnit(s)) {
      try {
        if (!unit.isUpToDate)
          if (unit.problems.isEmpty || !settings.YpresentationStrict.value)
            typeCheck(unit)
          else debugLog("%s has syntax errors. Skipped typechecking".format(unit))
        else debugLog("already up to date: "+unit)
        for (r <- waitLoadedTypeResponses(unit.source))
          r set unit.body
        serviceParsedEntered()
      } catch {
        case ex: FreshRunReq => throw ex           // propagate a new run request
        case ShutdownReq     => throw ShutdownReq  // propagate a shutdown request
        case ex: ControlThrowable => throw ex
        case ex: Throwable =>
          println("[%s]: exception during background compile: ".format(unit.source) + ex)
          ex.printStackTrace()
          for (r <- waitLoadedTypeResponses(unit.source)) {
            r.raise(ex)
          }
          serviceParsedEntered()

          lastException = Some(ex)
          ignoredFiles += unit.source.file
          println("[%s] marking unit as crashed (crashedFiles: %s)".format(unit, ignoredFiles))

          reporter.error(unit.body.pos, "Presentation compiler crashed while type checking this file: %s".format(ex.toString()))
      }
    }

    // move units removable after this run to the "to-be-removed" buffer
    toBeRemoved.synchronized {
      toBeRemovedAfterRun.synchronized {
        toBeRemoved ++= toBeRemovedAfterRun
      }
    }

    // clean out stale waiting responses
    cleanAllResponses()

    // wind down
    if (waitLoadedTypeResponses.nonEmpty || getParsedEnteredResponses.nonEmpty) {
      // need another cycle to treat those
      newTyperRun()
      backgroundCompile()
    } else {
      outOfDate = false
      informIDE("Everything is now up to date")
    }
  }

  /** Service all pending getParsedEntered requests
   */
  private def serviceParsedEntered(): Unit = {
    var atOldRun = true
    for ((source, rs) <- getParsedEnteredResponses; r <- rs) {
      if (atOldRun) { newTyperRun(); atOldRun = false }
      getParsedEnteredNow(source, r)
    }
    getParsedEnteredResponses.clear()
  }

  /** Reset unit to unloaded state */
  private def reset(unit: RichCompilationUnit): Unit = {
    unit.depends.clear(): @nowarn("cat=deprecation")
    unit.defined.clear(): @nowarn("cat=deprecation")
    unit.synthetics.clear()
    unit.toCheck.clear()
    unit.checkedFeatures = Set()
    unit.targetPos = NoPosition
    unit.contexts.clear()
    unit.problems.clear()
    unit.body = EmptyTree
    unit.status = NotLoaded
    unit.transformed.clear()
  }

  /** Parse unit and create a name index, unless this has already been done before */
  private def parseAndEnter(unit: RichCompilationUnit): Unit =
    if (unit.status == NotLoaded) {
      debugLog("parsing: "+unit)
      runReporting.clearSuppressionsComplete(unit.source)
      currentTyperRun.compileLate(unit)
      if (debugIDE && !reporter.hasErrors) validatePositions(unit.body)
      if (!unit.isJava) syncTopLevelSyms(unit)
      unit.status = JustParsed
    }

  /** Make sure unit is typechecked
   */
  private[scala] def typeCheck(unit: RichCompilationUnit): Unit = {
    debugLog("type checking: "+unit)
    parseAndEnter(unit)
    unit.status = PartiallyChecked
    currentTyperRun.typeCheck(unit)
    unit.lastBody = unit.body
    unit.status = currentRunId
  }

  /** Update deleted and current top-level symbols sets */
  def syncTopLevelSyms(unit: RichCompilationUnit): Unit = {
    val deleted = currentTopLevelSyms filter { sym =>
      /** We sync after namer phase and it resets all the top-level symbols
       *  that survive the new parsing
       *  round to NoPeriod.
       */
      sym.sourceFile == unit.source.file &&
      sym.validTo != NoPeriod &&
      runId(sym.validTo) < currentRunId
    }
    for (d <- deleted) {
      d.owner.info.decls unlink d
      deletedTopLevelSyms.synchronized { deletedTopLevelSyms += d }
      currentTopLevelSyms -= d
    }
  }

  /** Move list of files to front of allSources */
  def moveToFront(fs: List[SourceFile]): Unit = {
    allSources = fs ::: (allSources diff fs)
  }

  // ----------------- Implementations of client commands -----------------------

  def respond[T](result: Response[T])(op: => T): Unit =
    respondGradually(result)(LazyList(op))

  def respondGradually[T](response: Response[T])(op: => LazyList[T]): Unit = {
    val prevResponse = pendingResponse
    try {
      pendingResponse = response
      if (!response.isCancelled) {
        var results = op
        while (!response.isCancelled && results.nonEmpty) {
          val result = results.head
          results = results.tail
          if (results.isEmpty) {
            response set result
            debugLog("responded"+timeStep)
          } else response setProvisionally result
        }
      }
    } catch {
      case CancelException =>
        debugLog("cancelled")
      case ex: FreshRunReq =>
        if (debugIDE) {
          println("FreshRunReq thrown during response")
          ex.printStackTrace()
        }
        response raise ex
        throw ex

      case ex @ ShutdownReq =>
        if (debugIDE) {
          println("ShutdownReq thrown during response")
          ex.printStackTrace()
        }
        response raise ex
        throw ex

      case ex: Throwable =>
        if (debugIDE) {
          println("exception thrown during response: "+ex)
          ex.printStackTrace()
        }
        response raise ex
    } finally {
      pendingResponse = prevResponse
    }
  }

  private[interactive] def reloadSource(source: SourceFile): Unit = {
    val unit = new RichCompilationUnit(source)
    unitOfFile(source.file) = unit
    toBeRemoved.synchronized { toBeRemoved -= source.file }
    toBeRemovedAfterRun.synchronized { toBeRemovedAfterRun -= source.file }
    reset(unit)
    //parseAndEnter(unit)
  }

  /** Make sure a set of compilation units is loaded and parsed */
  private def reloadSources(sources: List[SourceFile]): Unit = {
    newTyperRun()
    minRunId = currentRunId
    sources foreach reloadSource
    moveToFront(sources)
  }

  /** Make sure a set of compilation units is loaded and parsed */
  private[interactive] def reload(sources: List[SourceFile], response: Response[Unit]): Unit = {
    informIDE("reload: " + sources)
    lastWasReload = true
    respond(response)(reloadSources(sources))
    demandNewCompilerRun()
  }

  private[interactive] def filesDeleted(sources: List[SourceFile], response: Response[Unit]): Unit = {
    informIDE("files deleted: " + sources)
    val deletedFiles = sources.map(_.file).toSet
    val deletedSyms = currentTopLevelSyms filter {sym => deletedFiles contains sym.sourceFile}
    for (d <- deletedSyms) {
      d.owner.info.decls unlink d
      deletedTopLevelSyms.synchronized { deletedTopLevelSyms += d }
      currentTopLevelSyms -= d
    }
    sources foreach (removeUnitOf(_))
    minRunId = currentRunId
    respond(response)(())
    demandNewCompilerRun()
  }

  /** Arrange for unit to be removed after run, to give a chance to typecheck the unit fully.
   *  If we do just removeUnit, some problems with default parameters can ensue.
   *  Calls to this method could probably be replaced by removeUnit once default parameters are handled more robustly.
   */
  private def afterRunRemoveUnitsOf(sources: List[SourceFile]): Unit = {
    toBeRemovedAfterRun.synchronized { toBeRemovedAfterRun ++= sources map (_.file) }
  }

  /** A fully attributed tree located at position `pos` */
  private[scala] def typedTreeAt(pos: Position): Tree = getUnit(pos.source) match {
    case None =>
      reloadSources(List(pos.source))
      try typedTreeAt(pos)
      finally afterRunRemoveUnitsOf(List(pos.source))
    case Some(unit) =>
      informIDE("typedTreeAt " + pos)
      parseAndEnter(unit)
      val tree = locateTree(pos)
      debugLog("at pos "+pos+" was found: "+tree.getClass+" "+tree.pos.show)
      tree match {
        case Import(expr, _) =>
          debugLog("import found"+expr.tpe+(if (expr.tpe == null) "" else " "+expr.tpe.members))
        case _ =>
      }
      if (stabilizedType(tree) ne null) {
        debugLog("already attributed: "+tree.symbol+" "+tree.tpe)
        tree
      } else {
        unit.targetPos = pos
        try {
          debugLog("starting targeted type check")
          typeCheck(unit)
//          println("tree not found at "+pos)
          EmptyTree
        } catch {
          case ex: TyperResult => new Locator(pos) locateIn ex.tree
        } finally {
          unit.targetPos = NoPosition
        }
      }
  }

  /** A fully attributed tree corresponding to the entire compilation unit  */
  private[interactive] def typedTree(source: SourceFile, forceReload: Boolean): Tree = {
    informIDE("typedTree " + source + " forceReload: " + forceReload)
    val unit = getOrCreateUnitOf(source)
    if (forceReload) reset(unit)
    parseAndEnter(unit)
    if (unit.status <= PartiallyChecked) typeCheck(unit)
    unit.body
  }

  /** Set sync var `response` to a fully attributed tree located at position `pos`  */
  private[interactive] def getTypedTreeAt(pos: Position, response: Response[Tree]): Unit = {
    respond(response)(typedTreeAt(pos))
  }

  /** Set sync var `response` to a fully attributed tree corresponding to the
   *  entire compilation unit  */
  private[interactive] def getTypedTree(source: SourceFile, forceReload: Boolean, response: Response[Tree]): Unit = {
    respond(response)(typedTree(source, forceReload))
  }

  private def withTempUnits[T](sources: List[SourceFile])(f: (SourceFile => RichCompilationUnit) => T): T = {
    val unitOfSrc: SourceFile => RichCompilationUnit = src => unitOfFile(src.file)
    sources filterNot (getUnit(_).isDefined) match {
      case Nil =>
        f(unitOfSrc)
      case unknown =>
        reloadSources(unknown)
        try {
          f(unitOfSrc)
        } finally
          afterRunRemoveUnitsOf(unknown)
    }
  }

  private def withTempUnit[T](source: SourceFile)(f: RichCompilationUnit => T): T =
    withTempUnits(List(source)){ srcToUnit =>
      f(srcToUnit(source))
    }

  /** Find a 'mirror' of symbol `sym` in unit `unit`. Pre: `unit is loaded. */
  private def findMirrorSymbol(sym: Symbol, unit: RichCompilationUnit): Symbol = {
    val originalTypeParams = sym.owner.typeParams
    ensureUpToDate(unit)
    parseAndEnter(unit)
    val pre = adaptToNewRunMap(ThisType(sym.owner))
    val rawsym = pre.typeSymbol.info.decl(sym.name)
    val newsym = rawsym filter { alt =>
      sym.isType || {
        try {
          val tp1 = pre.memberType(alt) onTypeError NoType
          val tp2 = adaptToNewRunMap(sym.tpe).substSym(originalTypeParams, sym.owner.typeParams)
          matchesType(tp1, tp2, alwaysMatchSimple = false) || {
            debugLog(s"findMirrorSymbol matchesType($tp1, $tp2) failed")
            val tp3 = adaptToNewRunMap(sym.tpe).substSym(originalTypeParams, alt.owner.typeParams)
            matchesType(tp1, tp3, alwaysMatchSimple = false) || {
              debugLog(s"findMirrorSymbol fallback matchesType($tp1, $tp3) failed")
              false
            }
          }
        }
        catch {
          case ex: ControlThrowable => throw ex
          case ex: Throwable =>
            debugLog("error in findMirrorSymbol: " + ex)
            ex.printStackTrace()
            false
        }
      }
    }
    if (newsym == NoSymbol) {
      if (rawsym.exists && !rawsym.isOverloaded) rawsym
      else {
        debugLog("mirror not found " + sym + " " + unit.source + " " + pre)
        NoSymbol
      }
    } else if (newsym.isOverloaded) {
      settings.uniqid.value = true
      debugLog("mirror ambiguous " + sym + " " + unit.source + " " + pre + " " + newsym.alternatives)
      NoSymbol
    } else {
      debugLog("mirror found for " + newsym + ": " + newsym.pos)
      newsym
    }
  }

  /** Implements CompilerControl.askLinkPos */
  private[interactive] def getLinkPos(sym: Symbol, source: SourceFile, response: Response[Position]): Unit = {
    informIDE("getLinkPos "+sym+" "+source)
    respond(response) {
      if (sym.owner.isClass) {
        withTempUnit(source){ u =>
          findMirrorSymbol(sym, u).pos
        }
      } else {
        debugLog("link not in class "+sym+" "+source+" "+sym.owner)
        NoPosition
      }
    }
  }

  private def forceDocComment(sym: Symbol, unit: RichCompilationUnit): Unit = {
    unit.body foreachPartial {
      case DocDef(comment, defn) if defn.symbol == sym =>
        fillDocComment(defn.symbol, comment)
        EmptyTree
      case _: ValOrDefDef =>
        EmptyTree
    }
  }

  /** Implements CompilerControl.askDocComment */
  private[interactive] def getDocComment(sym: Symbol, source: SourceFile, site: Symbol, fragments: List[(Symbol,SourceFile)],
                                         response: Response[(String, String, Position)]): Unit = {
    informIDE(s"getDocComment $sym at $source, site $site")
    respond(response) {
      withTempUnits(fragments.unzip._2){ units =>
        for((sym, src) <- fragments) {
          val mirror = findMirrorSymbol(sym, units(src))
          if (mirror ne NoSymbol) forceDocComment(mirror, units(src))
        }
        val mirror = findMirrorSymbol(sym, units(source))
        if (mirror eq NoSymbol)
          ("", "", NoPosition)
        else {
          (expandedDocComment(mirror, site), rawDocComment(mirror), docCommentPos(mirror))
        }
      }
    }
    // New typer run to remove temp units and drop per-run caches that might refer to symbols entered from temp units.
    newTyperRun()
  }

  def stabilizedType(tree: Tree): Type = tree match {
    case Ident(_) if treeInfo.admitsTypeSelection(tree) =>
      singleType(NoPrefix, tree.symbol)
    case Select(qual, _) if treeInfo.admitsTypeSelection(tree) =>
      singleType(qual.tpe, tree.symbol)
    case Import(_, _) =>
      tree.symbol.info match {
        case ImportType(expr) => expr match {
          case Select(qual, _) if treeInfo.admitsTypeSelection(expr) => singleType(qual.tpe, expr.symbol)
          case _: Ident => expr.tpe
          case _ => tree.tpe
        }
        case _ => tree.tpe
      }
    case _ => tree.tpe
  }

  import analyzer.{ImplicitSearch, SearchResult}

  private[interactive] def getScopeCompletion(pos: Position, response: Response[List[Member]]): Unit = {
    informIDE("getScopeCompletion" + pos)
    respond(response) { scopeMemberFlatten(scopeMembers(pos)) }
  }

  @nowarn("msg=inheritance from class LinkedHashMap")
  private class Members[M <: Member] extends LinkedHashMap[Name, Set[M]] {
    override def default(key: Name) = Set()

    private def matching(sym: Symbol, symtpe: Type, ms: Set[M]): Option[M] = ms.find { m =>
      (m.sym.name == sym.name) && (m.sym.isType || (m.tpe matches symtpe))
    }

    private def keepSecond(m: M, sym: Symbol, implicitlyAdded: Boolean): Boolean =
      m.sym.hasFlag(ACCESSOR | PARAMACCESSOR) &&
      !sym.hasFlag(ACCESSOR | PARAMACCESSOR) &&
      (!implicitlyAdded || m.implicitlyAdded)

    def add(sym: Symbol, pre: Type, implicitlyAdded: Boolean)(toMember: (Symbol, Type) => M): Unit = {
      if ((sym.isGetter || sym.isSetter) && sym.accessed != NoSymbol) {
        add(sym.accessed, pre, implicitlyAdded)(toMember)
      } else if (!sym.name.decodedName.containsName("$") && !sym.isError && !sym.isArtifact && sym.hasRawInfo) {
        val symtpe = pre.memberType(sym) onTypeError ErrorType
        matching(sym, symtpe, this(sym.name)) match {
          case Some(m) =>
            if (keepSecond(m, sym, implicitlyAdded)) {
              //print(" -+ "+sym.name)
              this(sym.name) = this(sym.name) - m + toMember(sym, symtpe)
            }
          case None =>
            //print(" + "+sym.name)
            this(sym.name) = this(sym.name) + toMember(sym, symtpe)
        }
      }
    }

    def addNonShadowed(other: Members[M]) = {
      for ((name, ms) <- other)
        if (ms.nonEmpty && this(name).isEmpty) this(name) = ms
    }

    def allMembers: List[M] = values.toList.flatten
  }

  /** Return all members visible without prefix in context enclosing `pos`. */
  private def scopeMembers(pos: Position): List[ScopeMember] = {
    typedTreeAt(pos) // to make sure context is entered
    val context = doLocateContext(pos)
    val locals = new Members[ScopeMember]
    val enclosing = new Members[ScopeMember]
    def addScopeMember(sym: Symbol, pre: Type, viaImport: Tree) =
      locals.add(sym, pre, implicitlyAdded = false) { (s, st) =>
        // imported val and var are always marked as inaccessible, but they could be accessed through their getters. scala/bug#7995
        val member = if (s.hasGetter)
          ScopeMember(s, st, context.isAccessible(s.getter, pre, superAccess = false), viaImport)
        else {
          if (s.isAliasType) {
            val aliasInfo = ScopeMember(s, st, context.isAccessible(s, pre, superAccess = false), viaImport)
            ScopeMember(s.info.typeSymbol, s.info.typeSymbol.tpe,
                                              context.isAccessible(s.info.typeSymbol, pre, superAccess = false), viaImport,
                                              aliasInfo = Some(aliasInfo))
          } else ScopeMember(s, st, context.isAccessible(s, pre, superAccess = false), viaImport)
        }
        member.prefix = pre
        member
      }
    def localsToEnclosing() = {
      enclosing.addNonShadowed(locals)
      locals.clear()
    }
    //print("add scope members")
    var cx = context
    while (cx != NoContext) {
      for (sym <- cx.scope)
        addScopeMember(sym, NoPrefix, EmptyTree)
      localsToEnclosing()
      if (cx == cx.enclClass) {
        val pre = cx.prefix
        for (sym <- pre.members)
          addScopeMember(sym, pre, EmptyTree)
        localsToEnclosing()
      }
      cx = cx.outer
    }
    //print("\nadd imported members")
    for (imp <- context.imports) {
      val pre = imp.qual.tpe
      for (sym <- imp.allImportedSymbols)
        addScopeMember(sym, pre, imp.qual)
      localsToEnclosing()
    }
    // println()
    val result = enclosing.allMembers
//    if (debugIDE) for (m <- result) println(m)
    result
  }

  private[interactive] def getTypeCompletion(pos: Position, response: Response[List[Member]]): Unit = {
    informIDE("getTypeCompletion " + pos)
    respondGradually(response) { typeMembers(pos) }
    //if (debugIDE) typeMembers(pos)
  }

  // it's expected that later items in the `LazyList` supersede earlier items.
  // (once a second item becomes available, you entirely discard the first item,
  // rather than combine them)
  private def typeMembers(pos: Position): LazyList[List[TypeMember]] = {
    // Choosing which tree will tell us the type members at the given position:
    //   If pos leads to an Import, type the expr
    //   If pos leads to a Select, type the qualifier as long as it is not erroneous
    //     (this implies discarding the possibly incomplete name in the Select node)
    //   Otherwise, type the tree found at 'pos' directly.
    val tree0 = typedTreeAt(pos) match {
      case sel @ Select(qual, _) if sel.tpe == ErrorType => qual
      case Import(expr, _)                               => expr
      case t                                             => t
    }
    val context = doLocateContext(pos)
    val shouldTypeQualifier = tree0.tpe match {
      case null           => true
      case mt: MethodType => mt.isImplicit
      case pt: PolyType   => isImplicitMethodType(pt.resultType)
      case _              => false
    }

    // TODO: guard with try/catch to deal with ill-typed qualifiers.
    val tree = if (shouldTypeQualifier) analyzer newTyper context typedQualifier tree0 else tree0

    debugLog("typeMembers at "+tree+" "+tree.tpe)
    val superAccess = tree.isInstanceOf[Super]
    val members = new Members[TypeMember]

    def addTypeMember(sym: Symbol, pre: Type, inherited: Boolean, viaView: Symbol) = {
      val implicitlyAdded = viaView != NoSymbol
      members.add(sym, pre, implicitlyAdded) { (s, st) =>
        val result = new TypeMember(s, st,
          context.isAccessible(if (s.hasGetter) s.getterIn(s.owner) else s, pre, superAccess && !implicitlyAdded),
          inherited,
          viaView)
        result.prefix = pre
        result

      }
    }

    /** Create a function application of a given view function to `tree` and typechecked it.
     */
    def viewApply(view: SearchResult): Tree = {
      assert(view.tree != EmptyTree, "view.tree should be non-empty")
      val t = analyzer.newTyper(context.makeImplicit(reportAmbiguousErrors = false))
        .typed(Apply(view.tree, List(tree)) setPos tree.pos)
      if (!t.tpe.isErroneous) t
      else analyzer.newTyper(context.makeSilent(reportAmbiguousErrors = true))
        .typed(Apply(view.tree, List(tree)) setPos tree.pos)
        .onTypeError(EmptyTree)
    }

    val pre = stabilizedType(tree)

    val ownerTpe = tree.tpe match {
      case ImportType(expr) => expr.tpe
      case null => pre
      case MethodType(List(), rtpe) => rtpe
      case _ => tree.tpe
    }

    //print("add members")
    for (sym <- ownerTpe.members)
      addTypeMember(sym, pre, sym.owner != ownerTpe.typeSymbol, NoSymbol)
    members.allMembers #:: {
      //print("\nadd enrichment")
      val applicableViews: List[SearchResult] =
        if (ownerTpe.isErroneous) List()
        else new ImplicitSearch(
          tree, functionType(List(ownerTpe), AnyTpe), isView = true, isByNamePt = false,
          context0 = context.makeImplicit(reportAmbiguousErrors = false)).allImplicits
      for (view <- applicableViews) {
        val vtree = viewApply(view)
        val vpre = stabilizedType(vtree)
        for (sym <- vtree.tpe.members if sym.isTerm) {
          addTypeMember(sym, vpre, inherited = false, view.tree.symbol)
        }
      }
      LazyList(members.allMembers)
    }
  }

  sealed abstract class CompletionResult {
    type M <: Member
    def results: List[M]
    /** The (possibly partial) name detected that precedes the cursor */
    def name: Name
    /** Cursor Offset - positionDelta == position of the start of the name */
    def positionDelta: Int
    def forImport: Boolean
    def matchingResults(nameMatcher: (Name) => Name => Boolean = entered => candidate => candidate.startsWith(entered)): List[M] = {
      val enteredName = if (name == nme.ERROR) nme.EMPTY else name
      val matcher = nameMatcher(enteredName)
      results.filter { (member: Member) =>
        val symbol = member.sym
        def isStable = member.tpe.isStable || member.sym.isStable || member.sym.getterIn(member.sym.owner).isStable
        def isJunk = !symbol.exists || symbol.name.isEmpty || symbol.encodedName.charAt(0) == '<' // e.g. <byname>
        def nameTypeOk: Boolean = {
          forImport || // Completing an import: keep terms and types.
            symbol.name.isTermName == name.isTermName || // Keep names of the same type
            name.isTypeName && isStable // Completing a type: keep stable terms (paths)
        }
        // scala/bug#11846 aliasInfo should be match
        def aliasTypeOk: Boolean = {
          matcher(member.aliasInfo.map(_.sym.name).getOrElse(NoSymbol.name)) && !forImport && symbol.name.isTermName == name.isTermName
        }
        
        !isJunk && member.accessible && (name.isEmpty || (matcher(member.sym.name) || aliasTypeOk)
          && nameTypeOk)

      }
    }
  }
  object CompletionResult {
    final case class ScopeMembers(positionDelta: Int, results: List[ScopeMember], name: Name, forImport: Boolean) extends CompletionResult {
      type M = ScopeMember
    }
    final case class TypeMembers(positionDelta: Int, qualifier: Tree, tree: Tree, results: List[TypeMember], name: Name) extends CompletionResult {
      def forImport: Boolean = tree.isInstanceOf[Import]
      type M = TypeMember
    }
    case object NoResults extends CompletionResult {
      override def results = Nil
      override def name = nme.EMPTY
      override def positionDelta = 0
      override def forImport: Boolean = false
    }
  }

  private def scopeMemberFlatten(members: List[ScopeMember]): List[ScopeMember] = {
    val (infoWithoutAlias, infoWithAlias) = members.partition(_.aliasInfo.isEmpty)
    infoWithoutAlias ++ infoWithAlias ++ infoWithAlias.flatten(_.aliasInfo)
  }

  final def completionsAt(pos: Position): CompletionResult = {
    val focus1: Tree = typedTreeAt(pos)
    def typeCompletions(tree: Tree, qual: Tree, nameStart: Int, name: Name): CompletionResult = {
      val qualPos = qual.pos
      val saved = tree.tpe
      // Force `typeMembers` to complete via the prefix, not the type of the Select itself.
      tree.setType(ErrorType)
      val allTypeMembers = try {
        typeMembers(qualPos).last
      } finally {
        tree.setType(saved)
      }
      val positionDelta: Int = pos.start - nameStart
      val subName: Name = name.newName(new String(pos.source.content, nameStart, pos.start - nameStart)).encodedName
      CompletionResult.TypeMembers(positionDelta, qual, tree, allTypeMembers, subName)
    }
    focus1 match {
      case Apply(Select(qual, name), _) if qual.hasAttachment[InterpolatedString.type] =>
        // This special case makes CompletionTest.incompleteStringInterpolation work.
        // In incomplete code, the parser treats `foo""` as a nested string interpolation, even
        // though it is likely that the user wanted to complete `fooBar` before adding the closing brace.
        // val fooBar = 42; s"abc ${foo<TAB>"
        //
        // TODO: We could also complete the selection here to expand `ra<TAB>"..."` to `raw"..."`.
        val allMembers = scopeMembers(pos)
        val positionDelta: Int = pos.start - focus1.pos.start
        val subName = name.subName(0, positionDelta)
        CompletionResult.ScopeMembers(positionDelta, scopeMemberFlatten(allMembers), subName, forImport = false)
      case imp@Import(i @ Ident(name), head :: Nil) if head.name == nme.ERROR =>
        val allMembers = scopeMembers(pos)
        val nameStart = i.pos.start
        val positionDelta: Int = pos.start - nameStart
        val subName = name.subName(0, pos.start - i.pos.start)
        CompletionResult.ScopeMembers(positionDelta, scopeMemberFlatten(allMembers), subName, forImport = true)
      case imp@Import(qual, selectors) =>
        selectors.reverseIterator.find(_.namePos <= pos.start) match {
          case None => CompletionResult.NoResults
          case Some(selector) =>
            typeCompletions(imp, qual, selector.namePos, selector.name)
        }
      case sel@Select(qual, name) =>
        val qualPos = qual.pos
        val effectiveQualEnd = if (qualPos.isRange) qualPos.end else qualPos.point - 1
        def fallback = {
          effectiveQualEnd + 2
        }
        val source = pos.source

        val nameStart: Int = (focus1.pos.end - 1 to effectiveQualEnd by -1).find(p =>
          source.identFrom(source.position(p)).exists(_.length == 0)
        ).map(_ + 1).getOrElse(fallback)
        typeCompletions(sel, qual, nameStart, name)
      case Ident(name) =>
        val allMembers = scopeMembers(pos)
        val positionDelta: Int = pos.start - focus1.pos.start
        val subName = name.subName(0, positionDelta)
        CompletionResult.ScopeMembers(positionDelta, scopeMemberFlatten(allMembers), subName, forImport = false)
      case _ =>
        CompletionResult.NoResults
    }
  }


  /** Implements CompilerControl.askLoadedTyped */
  private[interactive] def waitLoadedTyped(source: SourceFile, response: Response[Tree], keepLoaded: Boolean, onSameThread: Boolean): Unit = {
    getUnit(source) match {
      case Some(unit) =>
        if (unit.isUpToDate) {
          debugLog("already typed")
          response set unit.body
        } else if (ignoredFiles(source.file)) {
          response.raise(lastException.getOrElse(CancelException))
        } else if (onSameThread) {
          getTypedTree(source, forceReload = false, response)
        } else {
          debugLog("wait for later")
          outOfDate = true
          waitLoadedTypeResponses(source) += response
        }
      case None =>
        debugLog("load unit and type")
        try reloadSources(List(source))
        finally {
          waitLoadedTyped(source, response, keepLoaded, onSameThread = true)
          if (!keepLoaded) removeUnitOf(source)
        }
    }
  }

  /** Implements CompilerControl.askParsedEntered */
  private[interactive] def getParsedEntered(source: SourceFile, keepLoaded: Boolean, response: Response[Tree], onSameThread: Boolean = true): Unit = {
    getUnit(source) match {
      case Some(unit) =>
        getParsedEnteredNow(source, response)
      case None =>
        try {
          if (keepLoaded || outOfDate && onSameThread)
            reloadSources(List(source))
        } finally {
          if (keepLoaded || !outOfDate || onSameThread)
            getParsedEnteredNow(source, response)
          else
            getParsedEnteredResponses(source) += response
        }
    }
  }

  /** Parses and enters given source file, storing parse tree in response */
  private def getParsedEnteredNow(source: SourceFile, response: Response[Tree]): Unit = {
    respond(response) {
      onUnitOf(source) { unit =>
        parseAndEnter(unit)
        unit.body
      }
    }
  }

  // ---------------- Helper classes ---------------------------

  /** The typer run */
  class TyperRun extends Run {
    // units is always empty

    /** canRedefine is used to detect double declarations of classes and objects
     *  in multiple source files.
     *  Since the IDE rechecks units several times in the same run, these tests
     *  are disabled by always returning true here.
     */
    override def canRedefine(sym: Symbol) = true

    def typeCheck(unit: CompilationUnit): Unit = {
      applyPhase(typerPhase, unit)
    }

    /** Apply a phase to a compilation unit
     *  @return true iff typechecked correctly
     */
    private def applyPhase(phase: Phase, unit: CompilationUnit): Unit = {
      enteringPhase(phase) { phase.asInstanceOf[GlobalPhase] applyPhase unit }
    }
  }

  def newTyperRun(): Unit = {
    currentTyperRun = new TyperRun
  }

  class TyperResult(val tree: Tree) extends ControlThrowable

  assert(globalPhase.id == 0, "phase at zero")

  implicit def addOnTypeError[T](x: => T): OnTypeError[T] = new OnTypeError(x)

  // OnTypeError should still catch TypeError because of cyclic references,
  // but DivergentImplicit shouldn't leak anymore here
  class OnTypeError[T](op: => T) {
    def onTypeError(alt: => T) = try {
      op
    } catch {
      case ex: TypeError =>
        debugLog("type error caught: "+ex)
        alt
    }
  }

  // We need to force a number of symbols that might be touched by a parser.
  // Otherwise thread safety property of parseTree method would be violated.
  protected def forceSymbolsUsedByParser(): Unit = {
    val symbols =
      Set(UnitClass, BooleanClass, ByteClass,
          ShortClass, IntClass, LongClass, FloatClass,
          DoubleClass, NilModule, ListClass, PredefModule) ++ TupleClass.seq ++ ArrayModule_overloadedApply.alternatives
    symbols.foreach(_.initialize)
  }

  forceSymbolsUsedByParser()

  /** Start the compiler background thread and turn on thread confinement checks */
  private def finishInitialization(): Unit = {
    // this flag turns on `assertCorrectThread checks`
    initializing = false

    // Only start the thread if initialization was successful. A crash while forcing symbols (for example
    // if the Scala library is not on the classpath) can leave running threads behind. See Scala IDE #1002016
    compileRunner.start()
  }

  /** The compiler has been initialized. Constructors are evaluated in textual order,
   *  if we reached here, all super constructors and the primary constructor
   *  have been executed.
   */
  finishInitialization()
}

object CancelException extends Exception
