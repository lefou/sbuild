package de.tototec.sbuild.execute

import java.io.File

import scala.collection.JavaConverters._
import scala.collection.parallel.ForkJoinTaskSupport
import scala.concurrent.Lock
import scala.concurrent.forkjoin.ForkJoinPool

import org.fusesource.jansi.Ansi
import org.fusesource.jansi.Ansi.Color.CYAN
import org.fusesource.jansi.Ansi.Color.GREEN
import org.fusesource.jansi.Ansi.Color.RED
import org.fusesource.jansi.Ansi.ansi

import de.tototec.sbuild.BuildScriptAware
import de.tototec.sbuild.ExecutionFailedException
import de.tototec.sbuild.LogLevel
import de.tototec.sbuild.Project
import de.tototec.sbuild.ProjectConfigurationException
import de.tototec.sbuild.SBuildLogger
import de.tototec.sbuild.Target
import de.tototec.sbuild.TargetAware
import de.tototec.sbuild.TargetContext
import de.tototec.sbuild.TargetContextImpl
import de.tototec.sbuild.TargetRef.fromString
import de.tototec.sbuild.UnsupportedSchemeException
import de.tototec.sbuild.WithinTargetExecution

object TargetExecutor {

  case class LogConfig(
    // showPercent: Boolean = true,
    executing: LogLevel = LogLevel.Info,
    topLevelSkipped: LogLevel = LogLevel.Info,
    subLevelSkipped: LogLevel = LogLevel.Debug // finished: Option[LogLevel] = Some(LogLevel.Debug))
    )

  //  case class ProcessConfig(parallelJobs: Option[Int] = None)

  class ExecProgress(val maxCount: Int, private[this] var _currentNr: Int = 1) {
    def currentNr = _currentNr
    def addToCurrentNr(addToCurrentNr: Int): Unit = synchronized { _currentNr += addToCurrentNr }
  }

  /**
   * This cache automatically resolves and caches dependencies of targets.
   *
   * It is assumed, that the involved projects are completely initialized and no new target will appear after this cache is active.
   */
  class DependencyCache(baseProject: Project) {
    private implicit val _baseProject = baseProject

    private var depTrees: Map[Target, Seq[Target]] = Map()

    def cached: Map[Target, Seq[Target]] = synchronized { depTrees }

    /**
     * Return the direct dependencies of the given target.
     *
     * If this Cache already contains a cached result, that one will be returned.
     * Else, the dependencies will be computed through [[de.tototec.sbuild.Project#prerequisites]].
     *
     * If the parameter `callStack` is not `Nil`, the call stack including the given target will be checked for cycles.
     * If a cycle is detected, a [[de.tototec.sbuild.ProjectConfigurationException]] will be thrown.
     *
     * @throws ProjectConfigurationException If cycles are detected.
     * @throws UnsupportedSchemeException If an unsupported scheme was used in any of the targets.
     */
    def targetDeps(target: Target, dependencyTrace: List[Target] = Nil): Seq[Target] = synchronized {
      // check for cycles
      dependencyTrace.find(dep => dep == target).map { cycle =>
        val ex = new ProjectConfigurationException("Cycles in dependency chain detected for: " + cycle.formatRelativeTo(baseProject) +
          ". The dependency chain: " + (target :: dependencyTrace).reverse.map(_.formatRelativeTo(baseProject)).mkString(" -> "))
        ex.buildScript = Some(cycle.project.projectFile)
        throw ex
      }

      depTrees.get(target) match {
        case Some(deps) => deps
        case None =>
          try {
            val deps = target.project.prerequisites(target = target, searchInAllProjects = true)
            depTrees += (target -> deps)
            deps
          } catch {
            case e: UnsupportedSchemeException =>
              val ex = new UnsupportedSchemeException("Unsupported Scheme in dependencies of target: " +
                target.formatRelativeTo(baseProject) + ". " + e.getMessage)
              ex.buildScript = e.buildScript
              ex.targetName = Some(target.formatRelativeTo(baseProject))
              throw ex
            case e: TargetAware if e.targetName == None =>
              e.targetName = Some(target.formatRelativeTo(baseProject))
              throw e
          }
      }
    }

    /**
     * Fills this cache by evaluating the given target and all its transitive dependencies.
     *
     * Internally, the method [[de.tototec.sbuild.runner.SBuildRunner.Cache#targetDeps]] is used.
     *
     * @throws ProjectConfigurationException If cycles are detected.
     * @throws UnsupportedSchemeException If an unsupported scheme was used in any of the targets.
     */
    def fillTreeRecursive(target: Target, parents: List[Target] = Nil): Unit = synchronized {
      targetDeps(target, parents).foreach { dep => fillTreeRecursive(dep, target :: parents) }
    }

  }

  /**
   * Context used when processing targets in parallel.
   *
   * @param threadCount If Some(count), the count of threads to be used.
   *   If None, then it defaults to the count of processor cores.
   */
  class ParallelExecContext(val threadCount: Option[Int] = None, val baseProject: Project) {
    private[this] var _locks: Map[Target, Lock] = Map().withDefault { _ => new Lock() }

    val pool = threadCount match {
      case None => new ForkJoinPool()
      case Some(threads) => new ForkJoinPool(threads)
    }

    def taskSupport = new ForkJoinTaskSupport(pool)

    private[this] var _firstError: Option[Throwable] = None
    def getFirstError(currentError: Throwable): Throwable = synchronized {
      _firstError match {
        case None =>
          _firstError = Some(currentError)
          currentError
        case Some(e) => e
      }
    }

    private[this] def getLock(target: Target): Lock = synchronized {
      _locks.get(target) match {
        case Some(l) => l
        case None =>
          val l = new Lock()
          _locks += (target -> l)
          l
      }
    }

    def lock(target: Target): Unit = {
      val lock = getLock(target)
      if (!lock.available) target.project.log.log(LogLevel.Debug, s"Waiting for target: ${target.formatRelativeTo(baseProject)}")
      lock.acquire
    }

    def unlock(target: Target): Unit = getLock(target).release

  }

}

class TargetExecutor(baseProject: Project,
                     log: SBuildLogger,
                     persistentTargetCache: PersistentTargetCache = new PersistentTargetCache(),
                     logConfig: TargetExecutor.LogConfig = TargetExecutor.LogConfig() // processConfig: TargetExecutor.ProcessConfig = TargetExecutor.ProcessConfig()
                     ) {

  private implicit val _baseProject = baseProject
  import TargetExecutor._

  /**
   * Visit a forest of targets, each target of parameter `request` is the root of a tree.
   * Each tree will search deep-first. If parameter `skipExec` is `true`, the associated actions will not executed.
   * If `skipExec` is `false`, for each target the up-to-date state will be evaluated,
   * and if the target is no up-to-date, the associated action will be executed.
   */
  def preorderedDependenciesForest(request: Seq[Target],
                                   execProgress: Option[TargetExecutor.ExecProgress] = None,
                                   skipExec: Boolean = false,
                                   dependencyTrace: List[Target] = List(),
                                   depth: Int = 0,
                                   treePrinter: Option[(Int, Target) => Unit] = None,
                                   dependencyCache: DependencyCache = new DependencyCache(baseProject),
                                   transientTargetCache: Option[TransientTargetCache] = None,
                                   treeParallelExecContext: Option[ParallelExecContext] = None): Seq[ExecutedTarget] =
    request.map { req =>
      preorderedDependenciesTree(
        curTarget = req,
        execProgress = execProgress,
        skipExec = skipExec,
        dependencyTrace = dependencyTrace,
        depth = depth,
        treePrinter = treePrinter,
        dependencyCache = dependencyCache,
        transientTargetCache = transientTargetCache,
        parallelExecContext = treeParallelExecContext
      )
    }

  /**
   * Visit each target of tree `node` deep-first.
   *
   * If parameter `skipExec` is `true`, the associated actions will not executed.
   * If `skipExec` is `false`, for each target the up-to-date state will be evaluated,
   * and if the target is no up-to-date, the associated action will be executed.
   */
  def preorderedDependenciesTree(curTarget: Target,
                                 execProgress: Option[TargetExecutor.ExecProgress] = None,
                                 skipExec: Boolean = false,
                                 dependencyTrace: List[Target] = Nil,
                                 depth: Int = 0,
                                 treePrinter: Option[(Int, Target) => Unit] = None,
                                 dependencyCache: DependencyCache = new DependencyCache(baseProject),
                                 transientTargetCache: Option[TransientTargetCache] = None,
                                 parallelExecContext: Option[ParallelExecContext] = None): ExecutedTarget = {

    def inner: ExecutedTarget = {

      val log = curTarget.project.log

      treePrinter match {
        case Some(printFunc) => printFunc(depth, curTarget)
        case _ =>
      }

      // if curTarget is already cached, use the cached result
      transientTargetCache.flatMap(_.get(curTarget)).map { cachedExecutedContext =>
        // push progress forward by the size of the cached result
        execProgress.map(_.addToCurrentNr(cachedExecutedContext.treeSize))
        return cachedExecutedContext
      }

      val dependencies: Seq[Target] = dependencyCache.targetDeps(curTarget, dependencyTrace)

      log.log(LogLevel.Debug, "Dependencies of " + curTarget.formatRelativeTo(baseProject) + ": " +
        (if (dependencies.isEmpty) "<none>" else dependencies.map(_.formatRelativeTo(baseProject)).mkString(" ~ ")))

      val executedDependencies: Seq[ExecutedTarget] = parallelExecContext match {
        case None =>
          dependencies.map { dep =>
            preorderedDependenciesTree(
              curTarget = dep,
              execProgress = execProgress,
              skipExec = skipExec,
              dependencyTrace = curTarget :: dependencyTrace,
              depth = depth + 1,
              treePrinter = treePrinter,
              dependencyCache = dependencyCache,
              transientTargetCache = transientTargetCache,
              parallelExecContext = parallelExecContext)
          }
        case Some(parCtx) =>
          // val parDeps = collection.parallel.mutable.ParArray(dependencies: _*)
          val parDeps = collection.parallel.immutable.ParVector(dependencies: _*)
          parDeps.tasksupport = parCtx.taskSupport

          val result = parDeps.map { dep =>
            preorderedDependenciesTree(
              curTarget = dep,
              execProgress = execProgress,
              skipExec = skipExec,
              dependencyTrace = curTarget :: dependencyTrace,
              depth = depth + 1,
              treePrinter = treePrinter,
              dependencyCache = dependencyCache,
              transientTargetCache = transientTargetCache,
              parallelExecContext = parallelExecContext)
          }

          result.seq.toSeq
      }

      // print dep-tree
      lazy val trace = dependencyTrace match {
        case Nil => ""
        case x =>
          var _prefix = "     "
          def prefix = {
            _prefix += "  "
            _prefix
          }
          x.map { "\n" + prefix + _.formatRelativeTo(baseProject) }.mkString
      }

      case class ExecBag(ctx: TargetContext, wasUpToDate: Boolean)

      def calcProgressPrefix = execProgress match {
        case Some(state) =>
          val progress = (state.currentNr, state.maxCount) match {
            case (c, m) if (c > 0 && m > 0) =>
              val p = (c - 1) * 100 / m
              fPercent("[" + math.min(100, math.max(0, p)) + "%] ")
            case (c, m) => "[" + c + "/" + m + "] "
          }
          progress
        case _ => ""
      }
      val colorTarget = if (dependencyTrace.isEmpty) { fMainTarget _ } else { fTarget _ }

      val execBag: ExecBag = if (skipExec) {
        // already known as up-to-date

        ExecBag(new TargetContextImpl(curTarget, 0, Seq()), true)

      } else {
        // not skipped execution, determine if dependencies were up-to-date

        log.log(LogLevel.Debug, "===> Current execution: " + curTarget.formatRelativeTo(baseProject) +
          " -> requested by: " + trace + " <===")

        log.log(LogLevel.Debug, "Executed dependency count: " + executedDependencies.size);

        lazy val depsLastModified: Long = dependenciesLastModified(executedDependencies)
        val ctx = new TargetContextImpl(curTarget, depsLastModified, executedDependencies.map(_.targetContext))
        if (!executedDependencies.isEmpty)
          log.log(LogLevel.Debug, s"Dependencies have last modified value '${depsLastModified}': " + executedDependencies.map(_.target.formatRelativeTo(baseProject)).mkString(","))

        val needsToRun: Boolean = curTarget.targetFile match {
          case Some(file) =>
            // file target
            if (!file.exists) {
              curTarget.project.log.log(LogLevel.Debug, s"""Target file "${file}" does not exists.""")
              true
            } else {
              val fileLastModified = file.lastModified
              if (fileLastModified < depsLastModified) {
                // On Linux, Oracle JVM always reports only seconds file time stamp,
                // even if file system supports more fine grained time stamps (e.g. ext4 supports nanoseconds)
                // So, it can happen, that files we just wrote seems to be older than targets, which reported "NOW" as their lastModified.
                curTarget.project.log.log(LogLevel.Debug, s"""Target file "${file}" is older (${fileLastModified}) then dependencies (${depsLastModified}).""")
                val diff = depsLastModified - fileLastModified
                if (diff < 1000 && fileLastModified % 1000 == 0 && System.getProperty("os.name").toLowerCase.contains("linux")) {
                  curTarget.project.log.log(LogLevel.Debug, s"""Assuming up-to-dateness. Target file "${file}" is only ${diff} msec older, which might be caused by files system limitations or Oracle Java limitations (e.g. for ext4).""")
                  false
                } else true

              } else false
            }
          case None if curTarget.action == null =>
            // phony target but just a collector of dependencies
            ctx.targetLastModified = depsLastModified
            val files = ctx.fileDependencies
            if (!files.isEmpty) {
              log.log(LogLevel.Debug, s"Attaching ${files.size} files of dependencies to empty phony target.")
              ctx.attachFileWithoutLastModifiedCheck(files)
            }
            false

          case None =>
            // ensure, that the persistent state gets erased, whenever a non-cacheble phony target runs
            curTarget.evictsCache.map { cacheName =>
              curTarget.project.log.log(LogLevel.Debug,
                s"""Target "${curTarget.name}" will evict the target state cache with name "${cacheName}" now.""")
              persistentTargetCache.dropCacheState(curTarget.project, cacheName)
            }
            // phony target, have to run it always. Any laziness is up to it implementation
            curTarget.project.log.log(LogLevel.Debug, s"""Target "${curTarget.name}" is phony and needs to run (if not cached).""")
            true
        }

        if (!needsToRun)
          log.log(LogLevel.Debug, "Target '" + curTarget.formatRelativeTo(baseProject) + "' does not need to run.")

        val progressPrefix = calcProgressPrefix

        val wasUpToDate: Boolean = if (!needsToRun) {
          val level = if (dependencyTrace.isEmpty) logConfig.topLevelSkipped else logConfig.subLevelSkipped
          log.log(level, progressPrefix + "Skipping target:  " + colorTarget(curTarget.formatRelativeTo(baseProject)))
          true
        } else { // needsToRun
          curTarget.action match {
            case null =>
              // Additional sanity check
              if (!curTarget.phony) {
                val ex = new ProjectConfigurationException(s"""Target "${curTarget.name}" has no defined execution. Don't know how to create or update file "${curTarget.file}".""")
                ex.buildScript = Some(curTarget.project.projectFile)
                ex.targetName = Some(curTarget.name)
                throw ex
              }
              log.log(LogLevel.Debug, progressPrefix + "Skipping target:  " + colorTarget(curTarget.formatRelativeTo(baseProject)))
              log.log(LogLevel.Debug, "Nothing to execute (no action defined) for target: " + curTarget.formatRelativeTo(baseProject))
              true
            case exec =>
              WithinTargetExecution.set(new WithinTargetExecution {
                override def targetContext: TargetContext = ctx
                override def directDepsTargetContexts: Seq[TargetContext] = ctx.directDepsTargetContexts
              })
              try {

                // if state is Some(_), it is already check to be up-to-date
                val cachedState: Option[persistentTargetCache.CachedState] =
                  if (curTarget.isCacheable) persistentTargetCache.loadOrDropCachedState(ctx)
                  else None

                val wasUpToDate: Boolean = cachedState match {
                  case Some(cache) =>
                    log.log(LogLevel.Debug, progressPrefix + "Skipping cached target: " + colorTarget(curTarget.formatRelativeTo(baseProject)))
                    ctx.start
                    ctx.targetLastModified = cachedState.get.targetLastModified
                    ctx.attachFileWithoutLastModifiedCheck(cache.attachedFiles)
                    ctx.end
                    true

                  case None =>
                    if (!curTarget.isSideeffectFree) transientTargetCache.map(_.evict)
                    val level = if (curTarget.isTransparentExec) LogLevel.Debug else logConfig.executing
                    log.log(level, progressPrefix + "Executing target: " + colorTarget(curTarget.formatRelativeTo(baseProject)))
                    log.log(LogLevel.Debug, "Target: " + curTarget)
                    if (curTarget.help != null && curTarget.help.trim != "")
                      log.log(level, progressPrefix + curTarget.help)
                    ctx.start
                    exec.apply(ctx)
                    ctx.end
                    log.log(LogLevel.Debug, s"Executed target '${curTarget.formatRelativeTo(baseProject)}' in ${ctx.execDurationMSec} msec")

                    // update persistent cache
                    if (curTarget.isCacheable) persistentTargetCache.writeCachedState(ctx)

                    false
                }

                ctx.targetLastModified match {
                  case Some(lm) =>
                    log.log(LogLevel.Debug, s"The context of target '${curTarget.formatRelativeTo(baseProject)}' reports a last modified value of '${lm}'.")
                  case _ =>
                }

                ctx.attachedFiles match {
                  case Seq() =>
                  case files =>
                    log.log(LogLevel.Debug, s"The context of target '${curTarget.formatRelativeTo(baseProject)}' has ${files.size} attached files")
                }

                wasUpToDate

              } catch {
                case e: TargetAware =>
                  ctx.end
                  if (e.targetName.isEmpty)
                    e.targetName = Some(curTarget.formatRelativeTo(baseProject))
                  log.log(LogLevel.Debug, s"Execution of target '${curTarget.formatRelativeTo(baseProject)}' aborted after ${ctx.execDurationMSec} msec with errors.\n${e.getMessage}", e)
                  throw e
                case e: Throwable =>
                  ctx.end
                  val ex = new ExecutionFailedException(s"Execution of target ${curTarget.formatRelativeTo(baseProject)} failed with an exception: ${e.getClass.getName}.\n${e.getMessage}", e.getCause, s"Execution of target ${curTarget.formatRelativeTo(baseProject)} failed with an exception: ${e.getClass.getName}.\n${e.getLocalizedMessage}")
                  ex.buildScript = Some(curTarget.project.projectFile)
                  ex.targetName = Some(curTarget.formatRelativeTo(baseProject))
                  log.log(LogLevel.Debug, s"Execution of target '${curTarget.formatRelativeTo(baseProject)}' aborted after ${ctx.execDurationMSec} msec with errors: ${e.getMessage}", e)
                  throw ex
              } finally {
                WithinTargetExecution.remove
              }
          }
        }

        ExecBag(ctx, wasUpToDate)
      }

      execProgress.map(_.addToCurrentNr(1))
      // when parallel, print some finish message
      if (!execBag.wasUpToDate && parallelExecContext.isDefined && !execBag.ctx.target.isTransparentExec) {
        val finishedPrefix = calcProgressPrefix
        log.log(LogLevel.Info, finishedPrefix + "Finished target: " + colorTarget(curTarget.formatRelativeTo(baseProject)) + " after " + execBag.ctx.execDurationMSec + " msec")
      }

      val executedTarget = new ExecutedTarget(targetContext = execBag.ctx, dependencies = executedDependencies)
      if (execBag.wasUpToDate && transientTargetCache.isDefined)
        transientTargetCache.get.cache(curTarget, executedTarget)

      executedTarget

    }

    parallelExecContext match {
      case None =>
        inner
      case Some(parCtx) =>
        parCtx.lock(curTarget)
        try {
          inner
        } catch {
          case e: Throwable =>
            log.log(LogLevel.Debug, "Catched an exception in parallel executed targets.", e)
            val firstError = parCtx.getFirstError(e)
            // we need to stop the complete ForkJoinPool
            parCtx.pool.shutdownNow()
            throw firstError
        } finally {
          parCtx.unlock(curTarget)
        }
    }

  }

  def dependenciesLastModified(dependencies: Seq[ExecutedTarget]): Long = {
    var lastModified: Long = 0
    def updateLastModified(lm: Long) {
      lastModified = math.max(lastModified, lm)
    }

    def now = System.currentTimeMillis

    dependencies.foreach { dep =>
      dep.target.targetFile match {
        case Some(file) if !file.exists =>
          log.log(LogLevel.Info, s"""The file "${file}" created by dependency "${dep.target.formatRelativeTo(baseProject)}" does no longer exists.""")
          updateLastModified(now)
        case Some(file) =>
          // file target and file exists, so we use its last modified
          updateLastModified(file.lastModified)
        case None =>
          // phony target, so we ask its target context 
          dep.targetContext.targetLastModified match {
            case Some(lm) =>
              // context has an associated last modified, which we will use
              updateLastModified(lm)
            case None =>
              // target context does not know something, so we fall back to a last modified of NOW
              updateLastModified(now)
          }
      }
    }
    lastModified
  }

  import org.fusesource.jansi.Ansi._
  import org.fusesource.jansi.Ansi.Color._

  // It seems, under windows bright colors are not displayed correctly
  val isWindows = System.getProperty("os.name").toLowerCase().contains("win")

  def fPercent(text: => String) =
    if (isWindows) ansi.fg(CYAN).a(text).reset
    else ansi.fgBright(CYAN).a(text).reset
  def fTarget(text: => String) = ansi.fg(GREEN).a(text).reset
  def fMainTarget(text: => String) = ansi.fg(GREEN).bold.a(text).reset
  def fOk(text: => String) = ansi.fgBright(GREEN).a(text).reset
  def fError(text: => String) =
    if (isWindows) ansi.fg(RED).a(text).reset
    else ansi.fgBright(RED).a(text).reset
  def fErrorEmph(text: => String) =
    if (isWindows) ansi.fg(RED).bold.a(text).reset
    else ansi.fgBright(RED).bold.a(text).reset

}
