package trufflestg

import trufflestg.data.Closure
import trufflestg.data.VoidInh
import trufflestg.data.whnf
import trufflestg.stg.CborModuleDir
import trufflestg.stg.TopLevel
import com.oracle.truffle.api.*
import com.oracle.truffle.api.TruffleLanguage.ContextPolicy
import com.oracle.truffle.api.debug.DebuggerTags
import com.oracle.truffle.api.instrumentation.ProvidedTags
import com.oracle.truffle.api.instrumentation.StandardTags.*
import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.interop.TruffleObject
import com.oracle.truffle.api.interop.UnsupportedMessageException
import com.oracle.truffle.api.nodes.NodeInfo
import com.oracle.truffle.api.source.SourceSection
import org.graalvm.options.OptionDescriptors
import org.graalvm.options.OptionValues
import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

const val LANGUAGE_ID = "haskell"
const val LANGUAGE_NAME = "Haskell"
const val LANGUAGE_VERSION = "0"
const val LANGUAGE_MIME_TYPE = "application/x-haskell"
const val LANGUAGE_EXTENSION = "hs"

@Suppress("unused")
private val LANGUAGE_BUILTIN_SOURCE by lazy { org.graalvm.polyglot.Source.newBuilder(LANGUAGE_ID, "", "[trufflestg builtin]").buildLiteral()!! }
private val LANGUAGE_SHEBANG_REGEXP by lazy { Pattern.compile("^#! ?/usr/bin/(env +runghc|runghc).*")!! }

@Suppress("unused")
private fun lookupNodeInfo(clazz: Class<*>?): NodeInfo? =
  if (clazz == null) null
  else clazz.getAnnotation<NodeInfo>(NodeInfo::class.java) ?: lookupNodeInfo(clazz.superclass)

private fun getMetaObject(value: Any?): String =
  if (value == null) "ANY"
  else {
    val interop = InteropLibrary.getFactory().getUncached(value)
    when {
      interop.isNumber(value) || value is Number -> "Number"
      interop.isBoolean(value) -> "Boolean"
      interop.isString(value) -> "String"
      interop.isExecutable(value) -> "Function"
      interop.isNull(value) -> "NULL"
      interop.hasMembers(value) -> "Object"
      else -> "Unsupported"
    }
  }

// crappy version of show
private fun toString(value: Any?): String =
  when(value) {
    null -> "null"
    is Number -> value.toString()
    is Closure -> value.toString()
    else -> {
      val interop = InteropLibrary.getFactory().getUncached(value)
      try {
        when {
          interop.fitsInLong(value) -> interop.asLong(value).toString()
          interop.isBoolean(value) -> java.lang.Boolean.toString(interop.asBoolean(value))
          interop.isString(value) -> interop.asString(value)
          interop.isNull(value) -> "NULL"
          interop.isExecutable(value) -> "Function"
          interop.hasMembers(value) -> "Object"
          else -> "Unsupported"
        }
      } catch (e: UnsupportedMessageException) {
        panic("toString: unknown type", e)
      }
    }
  }

@Option.Group("trufflestg")
@TruffleLanguage.Registration(
  id = LANGUAGE_ID,
  name = LANGUAGE_NAME,
  version = LANGUAGE_VERSION,
  defaultMimeType = LANGUAGE_MIME_TYPE,
  characterMimeTypes = [LANGUAGE_MIME_TYPE],
  contextPolicy = ContextPolicy.SHARED,
  fileTypeDetectors = [Language.Detector::class]
)
@ProvidedTags(
  CallTag::class, StatementTag::class, RootTag::class, RootBodyTag::class, ExpressionTag::class,
  DebuggerTags.AlwaysHalt::class
)
class Language : TruffleLanguage<Language.Context>() {
  class Detector : TruffleFile.FileTypeDetector {
    override fun findEncoding(@Suppress("UNUSED_PARAMETER") file: TruffleFile): Charset = StandardCharsets.UTF_8
    override fun findMimeType(file: TruffleFile): String? {
      val name = file.name ?: return null
      if (name.endsWith(LANGUAGE_EXTENSION)) return LANGUAGE_MIME_TYPE
      try {
        file.newBufferedReader(StandardCharsets.UTF_8).use { fileContent ->
          val firstLine = fileContent.readLine()
          if (firstLine != null && LANGUAGE_SHEBANG_REGEXP.matcher(firstLine).matches())
            return LANGUAGE_MIME_TYPE
        }
      } catch (e: IOException) { // ok
      } catch (e: SecurityException) { // ok
      }
      return null
    }
  }

  class Context(
    @Suppress("unused") val language: Language,
    var env: Env
  ) {
    val singleThreadedAssumption = Truffle.getRuntime().createAssumption("context is single threaded")!!
    fun shutdown() {}
  }

  private val singleContextAssumption = Truffle.getRuntime().createAssumption("Only a single context is active")!!
  override fun createContext(env: Env) = Context(this, env)
  override fun initializeContext(ctx: Context?) {}
  override fun finalizeContext(ctx: Context) = ctx.shutdown()
  override fun isObjectOfLanguage(obj: Any) = obj is TruffleObject
  override fun initializeMultipleContexts() = singleContextAssumption.invalidate()
  override fun areOptionsCompatible(a: OptionValues?, b: OptionValues?) = true
  override fun getOptionDescriptors(): OptionDescriptors? = null // Language.OPTION_DESCRIPTORS
  override fun initializeMultiThreading(ctx: Context) = ctx.singleThreadedAssumption.invalidate()
  override fun isThreadAccessAllowed(thread: Thread, singleThreaded: Boolean) = true
  override fun initializeThread(ctx: Context, thread: Thread?) {}
  override fun disposeThread(ctx: Context, thread: Thread?) {}
  override fun findMetaObject(ctx: Context, value: Any?): Any = getMetaObject(value)
  override fun findSourceLocation(ctx: Context, value: Any?): SourceSection? = null
  override fun isVisible(ctx: Context, value: Any?) = true
  override fun toString(ctx: Context, value: Any?): String = toString(value)
  override fun patchContext(ctx: Context, env: Env): Boolean {
    ctx.env = env
    return true
  }

//
//  // stubbed: for now inline parsing requests just return 'const'
//  override fun parse(request: InlineParsingRequest?): InlineCode {
//    val body = k(Nat, Nat)
//    return InlineCode(this, body)
//  }

  @ExperimentalStdlibApi
  override fun parse(request: ParsingRequest): CallTarget {
    val source = request.source

    val path = source.characters.toString()

    val d = CborModuleDir(this, path)

    val y = d["Main"]!!["main"]!!
    val z = whnf(y) as Closure

    val q = try {
      z.call(arrayOf(VoidInh))
    } catch (e: Exception) {
      e.printStackTrace()
      throw e
    }

    println(q)

    TODO("parse")
  }

//
//  override fun findExportedSymbol(context: Context?, globalName: String?, onlyExplicit: Boolean): Any? =
//    when (globalName) {
//      "S" -> s(Arr(Nat, Arr(Nat, Nat)), Arr(Nat, Nat), Nat)
//      "K" -> k(Nat, Nat)
//      "I" -> i(Nat)
//      "main" -> 42
//      else -> null
//    }

//  @Suppress("UNUSED_PARAMETER")
//  private fun s(tx: Type, ty: Type, tz: Type): Code = todo
//  private fun k(tx: Type, ty: Type) = binary({ x, _ -> x }, tx, ty)
//  private fun i(tx: Type) = unary({ x -> x }, tx)
//
//  @Suppress("UNUSED_PARAMETER")
//  inline fun unary(f: (x: Term) -> Term, argument: Type): Code = todo
//
//  @Suppress("UNUSED_PARAMETER")
//  inline fun binary(f: (x: Term, y: Term) -> Term, tx: Type, ty: Type): Code = todo

  companion object {
    fun currentLanguage(): Language = getCurrentLanguage(Language::class.java)

  }
}

