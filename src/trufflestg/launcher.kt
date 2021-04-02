package trufflestg

import org.fusesource.jansi.Ansi
import org.fusesource.jansi.Ansi.Attribute
import org.fusesource.jansi.Ansi.ansi
import org.fusesource.jansi.AnsiConsole
import org.graalvm.launcher.AbstractLanguageLauncher
import org.graalvm.options.OptionCategory
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Source
import java.io.File
import java.io.IOException
import java.nio.file.Paths
import java.util.*
import kotlin.system.exitProcess

fun <A> withAnsi(f: () -> A): A {
  AnsiConsole.systemInstall()
  return try {
    f()
  } finally {
    AnsiConsole.systemUninstall()
  }
}

internal fun PolyglotException.prettyStackTrace() {
//  if (isResourceExhausted)
  val stackTrace =
//    if (isResourceExhausted) stackTrace.toMutableList()
    polyglotStackTrace.toMutableList()
  if (!isInternalError && !isResourceExhausted) {
    val iterator = stackTrace.listIterator(stackTrace.size)
    while (iterator.hasPrevious()) {
      if (iterator.previous().isHostFrame) iterator.remove()
      else break
    }
  }
//  if (isResourceExhausted) stackTrace.forEach { println(it) }
  val out = ansi()
  //if (isHostException) out.fgRed().a(asHostException().toString())
  //else out.fgBrightYellow().a(message)
  out.a(when {
    isResourceExhausted -> "out of resources (stack or memory): $message"
    isHostException -> asHostException().toString()
    isExit -> "exit"
    isGuestException -> message
    else -> message
  })
  out.reset().a('\n').fgBrightBlack()
  stackTrace.forEach {
    out.a(Attribute.ITALIC).a("  at ").a(Attribute.ITALIC_OFF).a(it).a('\n')
  }
  out.reset()
  println(out.toString())
}

class Launcher : AbstractLanguageLauncher() {
  private var programArgs: Array<String> = emptyArray()
  private var versionAction: VersionAction = VersionAction.None
  private var file: File? = null

  override fun getLanguageId() = LANGUAGE_ID

  override fun launch(contextBuilder: Context.Builder) = exitProcess(execute(contextBuilder))

  private fun execute(contextBuilder: Context.Builder): Int = withAnsi { executeWithAnsi(contextBuilder) }

  private fun executeWithAnsi(contextBuilder: Context.Builder): Int {
    contextBuilder.arguments(languageId, programArgs)
    try {
      contextBuilder.build().use { ctx ->
        runVersionAction(versionAction, ctx.engine)
        val v = ctx.eval(Source.newBuilder(languageId, file).build())
        return if (v.canExecute()) v.execute().asInt() else v.asInt()
      }
    } catch (e: PolyglotException) {
      if (e.isExit) return e.exitStatus
      e.prettyStackTrace()
      return -1
    } catch (e: IOException) {
      println(
        ansi().a("Error loading file ")
        .bold().fgBrightBlack().a("'").reset().bold().a(file).boldOff().fgBrightBlack().a("'")
        .fgBrightBlack().a(" (").fgRed().a(e.message).fgBrightBlack().a(")").toString()
      )
      return -1
    }
  }

  override fun preprocessArguments(arguments: MutableList<String>, polyglotOptions: MutableMap<String, String>): List<String> {
    val unrecognizedOptions = ArrayList<String>()
    val iterator = arguments.listIterator()
    while (iterator.hasNext()) {
      val option = iterator.next()
      if (option.length < 2 || !option.startsWith("-")) {
        iterator.previous()
        break
      }
      // Ignore fall through
      when (option) {
        "--" -> { }
        "--show-version" -> versionAction = VersionAction.PrintAndContinue
        "--version" -> versionAction = VersionAction.PrintAndExit
        else -> {
          val equalsIndex = option.indexOf('=')
          val argument = when {
            equalsIndex > 0 -> option.substring(equalsIndex + 1)
            iterator.hasNext() -> iterator.next()
            else -> null
          }
          unrecognizedOptions.add(option)
          if (equalsIndex < 0 && argument != null) iterator.previous()
        }
      }
    }

    polyglotOptions["engine.TraceCompilation"] = "true"

    if (file == null && iterator.hasNext()) file = Paths.get(iterator.next()).toFile()
    val programArgumentsList = arguments.subList(iterator.nextIndex(), arguments.size)
    programArgs = programArgumentsList.toTypedArray()
    return unrecognizedOptions
  }

  override fun validateArguments(_polyglotOptions: Map<String, String>?) {
    if (file == null && versionAction != VersionAction.PrintAndExit)
      throw abort("no file provided", 6)
  }

  internal fun Ansi.ansiOption(option: String, description: String) {
    var o = option
    if (option.length >= 22) {
      format("%s%s\n", "  ", o)
      o = ""
    }
    format("  %-22s%s\n", o, description)
  }

  override fun printHelp(_maxCategory: OptionCategory) {
    ansi().run {
      newline()
      render("Usage: @|italic,blue trufflestg|@ @|bold [OPTION]|@... @|bold [FILE]|@ @|bold [PROGRAM ARGS]|@\n\n")
      a("Run haskell programs on GraalVM\n\n")
      a("Mandatory arguments to long options are mandatory for short options too.\n\n")
      a("Options:\n")
//      ansiOption("-L <path>", "set the path to search for cadenza libraries")
      ansiOption("--lib <library>", "add a library")
      ansiOption("--version", "print the version and exit")
      ansiOption("--show-version", "print the version and continue")
      println(toString())
    }
  }

  override fun collectArguments(args: MutableSet<String>) {
    args.addAll(listOf("-L", "--lib", "--version", "--show-version"))
  }

  override fun getDefaultLanguages(): Array<String> = arrayOf(LANGUAGE_ID) // "js","llvm",getLanguageId()};

  companion object {
    @JvmStatic
    fun main(args: Array<String>) {
      Launcher().launch(if (args.isEmpty()) arrayOf("/data/Code/ghc-whole-program-compiler-project/boyer.truffleghc/Main") else args)
    }
  }
}