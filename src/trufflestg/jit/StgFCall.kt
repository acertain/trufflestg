@file:Suppress("JAVA_MODULE_DOES_NOT_EXPORT_PACKAGE")
package trufflestg.jit

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.TruffleStackTrace
import com.oracle.truffle.api.dsl.Specialization
import com.oracle.truffle.api.exception.AbstractTruffleException
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.interop.ExceptionType
import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.library.ExportLibrary
import com.oracle.truffle.api.library.ExportMessage
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.source.Source
import jdk.internal.vm.annotation.Stable
import trufflestg.Language
import trufflestg.array_utils.map
import trufflestg.data.*
import trufflestg.panic
import trufflestg.stg.CborModuleDir
import trufflestg.stg.Stg
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest


fun getNFIType(x: Any): String = when (x) {
  is StgAddr.StgFFIAddr -> "POINTER"
  // FIXME: deal with Int8 etc somehow
  is StgWord -> "UINT64"
  is StgInt -> "SINT64"
  else -> TODO("getNFIType $x")
//  is TruffleObject -> when {
//    InteropLibrary.isNumber(x) -> when {}
//    else -> null
//  }
//  else -> null
}

fun Stg.PrimRep?.asNFIType(): String = when(this) {
  is Stg.PrimRep.AddrRep -> "POINTER"
  is Stg.PrimRep.IntRep -> "SINT64"
  is Stg.PrimRep.WordRep -> "UINT64"
  else -> TODO("$this asNFIType()")
}

fun dlopen(str: String): Any {
//  println("dlopen $str")
  val ctx = Language.currentContext().env
  val src = Source.newBuilder("nfi", "load (RTLD_GLOBAL) \"${str}\"", "(haskell ffi call)").build()
  return ctx.parseInternal(src).call()
}

@CompilerDirectives.CompilationFinal var rtsLoaded: Boolean = false
fun loadRts() {
  if (!rtsLoaded) {
    CompilerDirectives.transferToInterpreterAndInvalidate()
    rtsLoaded = true

    val libName = System.mapLibraryName("rts")

    val libFile = File.createTempFile("trufflestg-rts",libName)
    libFile.deleteOnExit()

    println("Module: ${StgFCall::class.java.module}")

    val s = StgFCall::class.java.getResourceAsStream("/$libName")
    if (s === null) {
      throw Exception("Can't find $libName: maybe trufflestg doesn't support ffi calls on your OS?")
    }
    Files.copy(s, libFile.toPath(), StandardCopyOption.REPLACE_EXISTING)

    println(libFile.path)
    dlopen(libFile.path)
    dlopen("/home/zcarterc/Sync/Code/cadenza/src/c/librts.so")
  }
}



val realFFICalls: MutableSet<String> = mutableSetOf()

class StgFCall(
  val type: Stg.Type,
  val x: Stg.ForeignCall,
  @field:Children val args: Array<Arg>
) : Code(null) {
  val name: String = (x.ctarget as Stg.CCallTarget.StaticTarget).string
  @Child var opNode: StgPrimOp? = primFCalls[name]?.let { it() }
  @Child var realFCall: StgRealFCall? = if (opNode === null) StgRealFCall(type, x) else null

  override fun execute(frame: VirtualFrame): Any {
    val xs = map(args) { it.execute(frame) }
    return if (opNode != null) {
      opNode!!.run(frame, xs)
    } else {
      realFCall!!.execute(xs)
    }
  }
}

class StgRealFCall(
  val type: Stg.Type,
  val x: Stg.ForeignCall
) : Node() {
  val name: String = (x.ctarget as Stg.CCallTarget.StaticTarget).string
  private val retType: Stg.PrimRep? = when (type) {
    is Stg.Type.UnboxedTuple -> when (type.rep.size) {
      0 -> null
      1 -> type.rep[0]
      else -> panic{"foreign call $x $type returning a multiple element unboxed tuple"}
    }
    is Stg.Type.PolymorphicRep -> panic("foreign call without known return kind")
    is Stg.Type.SingleValue -> type.rep
  }

  // TODO: should limit be 1?
  @Child var interop: InteropLibrary = InteropLibrary.getFactory().createDispatched(1)

  @CompilerDirectives.CompilationFinal var boundFn: Any? = null

  fun execute(xs: Array<Any>): Any {
    val ys = xs.dropLast(1).toTypedArray()
    if (boundFn === null) {
      CompilerDirectives.transferToInterpreterAndInvalidate()

      if (name !in realFFICalls) {
        realFFICalls.add(name)
        println("trufflestg: new real ffi call: $name $x ${xs.contentToString()}")
      }

      loadRts()
      val lib = (rootNode as ClosureRootNode).module.moduleDir.nativeLib()

      val interopSlow = InteropLibrary.getUncached()
      val fn = interopSlow.readMember(lib, name)

      if (xs.last() !is VoidInh) {
        panic { "foreign call with non-RealWorld last argument, TODO" }
      }

      val tyString = "(${ys.joinToString(",") { getNFIType(it) }}):${retType.asNFIType()}"
      boundFn = interopSlow.invokeMember(fn, "bind", tyString)
    }

    val r = interop.execute(boundFn, *ys)
    return when (retType) {
      is Stg.PrimRep.AddrRep -> UnboxedTuple(arrayOf(StgAddr.StgFFIAddr(interop.asPointer(r))))
      is Stg.PrimRep.IntRep -> UnboxedTuple(arrayOf(StgInt(interop.asLong(r))))
      is Stg.PrimRep.WordRep -> UnboxedTuple(arrayOf(StgWord(interop.asLong(r).toULong()))) // TODO: is this right?
      else -> {
        panic{"foreign call return nyi $x ${xs.contentToString()} $retType"}
      }
    }
  }
}

fun ByteArray.asCString(): String = String(copyOfRange(0, indexOf(0x00)))
val posix = jnr.posix.POSIXFactory.getPOSIX()
val zeroBytes: ByteArray = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)

@CompilerDirectives.TruffleBoundary fun printStackTrace() {
  var last: String? = null
  TruffleStackTrace.getStackTrace(Exception()).forEach {
    val s = "  at <haskell> ${it.target.rootNode.name}"
    if (s != last) println(s)
    last = s
  }
}

@ExportLibrary(InteropLibrary::class)
class TruffleStgExitException(val status: Int) : AbstractTruffleException() {
  @ExportMessage fun getExceptionType(): ExceptionType = ExceptionType.EXIT
  @ExportMessage fun getExceptionExitStatus(): Int = status
}

// mutable state
var md5: MessageDigest? = null

val GlobalStore: MutableMap<String, StablePtr> = mutableMapOf()

@OptIn(ExperimentalUnsignedTypes::class)
val primFCalls: Map<String, () -> StgPrimOp> =
  listOf(
    "GHCConcSignalSignalHandlerStore",
    "GHCConcWindowsPendingDelaysStore",
    "GHCConcWindowsIOManagerThreadStore",
    "GHCConcWindowsProddingStore",
    "SystemEventThreadEventManagerStore",
    "SystemEventThreadIOManagerThreadStore",
    "SystemTimerThreadEventManagerStore",
    "SystemTimerThreadIOManagerThreadStore",
    "LibHSghcFastStringTable",
    "LibHSghcPersistentLinkerState",
    "LibHSghcInitLinkerDone",
    "LibHSghcGlobalDynFlags",
    "LibHSghcStaticOptions",
    "LibHSghcStaticOptionsReady",
    "MaxStoreKey"
  ).associate {
    ("getOrSet$it") to wrap2 { a: StablePtr, _: VoidInh ->
      synchronized(GlobalStore) {
        val x = GlobalStore[it]
        UnboxedTuple(arrayOf(if (x === null) {
          GlobalStore[it] = a
          a
        } else {
          x
        }))
      }
    }
  } + mapOf(
  // TODO: do something safer! (use x to store MessageDigest object?)
  "__hsbase_MD5Init" to wrap2Boundary { x: StgAddr, y: VoidInh ->
    if (md5 != null) panic("")
    md5 = MessageDigest.getInstance("MD5")
    y
  },
  "__hsbase_MD5Update" to wrap4 { _: StgAddr, y: StgAddr, z: StgInt, v: VoidInh ->
    val c = y.getRange(0 until z.x.toInt())
    md5!!.update(c)
    v
  },
  "__hsbase_MD5Final" to wrap3Boundary { out: StgAddr, _: StgAddr, v: VoidInh ->
    out.write(0, md5!!.digest())
    md5 = null
    v
  },

  "errorBelch2" to wrap3Boundary { x: StgAddr, y: StgAddr, v: VoidInh ->
    // TODO: this is supposed to be printf
    // TODO: use Language.currentContext().env.{in,out,err} instead of System.blah everywhere
    System.err.println("errorBelch2: ${x.asCString()} ${y.asCString()}")
    v
  },
  "debugBelch2" to wrap3Boundary { x: StgAddr, y: StgAddr, v: VoidInh ->
    System.err.println("debugBelch2: ${x.asCString()} ${y.asCString()}"); v
  },

  "rts_setMainThread" to wrap2 { x: WeakRef, _: VoidInh -> UnboxedTuple(arrayOf()) },

  // TODO
  "isatty" to wrap2 { x: StgInt, _: VoidInh -> UnboxedTuple(arrayOf(StgInt(if (x.x in 0..2) 1L else 0L))) },
//  "fdReady" to { object : StgPrimOp(5) {
//    // FIXME: implement this, might need to use JNI
//    override fun run(frame: VirtualFrame, args: Array<Any>): Any {
//      val fd = (args[0] as StgInt).x
//      if (fd == 1L || fd == 0L) return UnboxedTuple(arrayOf(StgInt(1L)))
//      panic("todo: fdReady ${args[0]}")
//    }
//  } },

  "rintDouble" to wrap2 { x: StgDouble, _: VoidInh -> UnboxedTuple(arrayOf(StgDouble(Math.rint(x.x)))) },

  "rtsSupportsBoundThreads" to wrap1 { _: VoidInh -> UnboxedTuple(arrayOf(StgInt(0L))) },

  "initGCStatistics" to wrap1 { v: VoidInh -> v },

//  "ghczuwrapperZC20ZCbaseZCSystemziPosixziInternalsZCwrite" to wrap4Boundary { x: StgInt, y: StgAddr, z: StgWord, _: VoidInh ->
//    // stdout
//    if (x.x == 1L) {
//      val s = y.getRange(0 until z.x.toInt())
//      print(String(s))
//      UnboxedTuple(arrayOf(StgInt(z.x.toLong())))
//    } else {
//      panic("nyi ghczuwrapperZC20ZCbaseZCSystemziPosixziInternalsZCwrite")
//    }
//  },
//  "ghczuwrapperZC22ZCbaseZCSystemziPosixziInternalsZCread" to wrap4Boundary { x: StgInt, y: StgAddr, z: StgWord, _: VoidInh ->
//    UnboxedTuple(arrayOf(StgInt(posix.read(x.x.toInt(), y.asBuffer(), z.x.toLong()))))
//  },

  // TODO
  "lockFile" to { object : StgPrimOp(5) {
    override fun run(frame: VirtualFrame, args: Array<Any>): Any = UnboxedTuple(arrayOf(StgInt(0L)))
  } },
  "unlockFile" to wrap2 { x: StgInt, _: VoidInh -> UnboxedTuple(arrayOf(StgInt(0L))) },

  "shutdownHaskellAndExit" to wrap3Boundary { x: StgInt, _: StgInt, _: VoidInh -> throw TruffleStgExitException(x.x.toInt()) },

  "hs_free_stable_ptr" to wrap2 { x: StablePtr, _: VoidInh ->
    // FIXME: getting deRefStablePtr after freeStablePtr, so i'm disabling this for now
//    x.x = null
    UnboxedTuple(arrayOf())
  },
  "localeEncoding" to wrap1Boundary { _: VoidInh ->
    UnboxedTuple(arrayOf(StgAddr.fromArray("UTF-8".toByteArray() + zeroBytes)))
  },
  "stg_sig_install" to wrap4 { x: StgInt, y: StgInt, z: StgAddr, _: VoidInh ->
    // TODO
    UnboxedTuple(arrayOf(StgInt(-1)))
  },
  "getProgArgv" to wrap3Boundary { argc: StgAddr, argv: StgAddr, _: VoidInh ->
    val args = arrayOf(
      "trufflestg",
      *Language.currentContext().env.applicationArguments
    )

    val bs = args.map { it.toByteArray() + 0x00 }

    val n = bs.sumBy { it.size + 8 }
    val addr = unsafe.allocateMemory(n.toLong())
    val buf = newDirectByteBuffer(addr, n)

    val ixs = bs.map { a ->
      val ix = buf.position()
      buf.put(a)
      ix + addr
    }

    val argvIx = buf.position() + addr
    ixs.forEach { buf.putLong(it) }

    argc.writeLong(0, ixs.size.toLong())
    argv.writeLong(0, argvIx)
    UnboxedTuple(arrayOf())
  },
  "u_towupper" to wrap2 { x: StgInt, _: VoidInh ->
    UnboxedTuple(arrayOf(StgInt(Character.toUpperCase(x.x.toInt()).toLong())))
  },
  "u_gencat" to wrap2 { x: StgInt, _: VoidInh ->
    UnboxedTuple(arrayOf(StgInt(when (Character.getType(x.x.toInt()).toByte()) {
      // in java's order, mapping to haskell's order
      Character.UNASSIGNED -> 29
      Character.UPPERCASE_LETTER -> 0
      Character.LOWERCASE_LETTER -> 1
      Character.TITLECASE_LETTER -> 2
      Character.MODIFIER_LETTER -> 3
      Character.OTHER_LETTER -> 4
      Character.NON_SPACING_MARK -> 5
      Character.ENCLOSING_MARK -> 7
      Character.COMBINING_SPACING_MARK -> 6
      Character.DECIMAL_DIGIT_NUMBER -> 8
      Character.LETTER_NUMBER -> 9
      Character.OTHER_NUMBER -> 10
      Character.SPACE_SEPARATOR -> 22
      Character.LINE_SEPARATOR -> 23
      Character.PARAGRAPH_SEPARATOR -> 24
      Character.CONTROL -> 25
      Character.FORMAT -> 26
      Character.PRIVATE_USE -> 28
      Character.SURROGATE -> 27
      Character.DASH_PUNCTUATION -> 12
      Character.START_PUNCTUATION -> 13
      Character.END_PUNCTUATION -> 14
      Character.CONNECTOR_PUNCTUATION -> 11
      Character.OTHER_PUNCTUATION -> 17
      Character.MATH_SYMBOL -> 18
      Character.CURRENCY_SYMBOL -> 19
      Character.MODIFIER_SYMBOL -> 20
      Character.OTHER_SYMBOL -> 21
      Character.INITIAL_QUOTE_PUNCTUATION -> 15
      Character.FINAL_QUOTE_PUNCTUATION -> 16
      else -> panic("java.lang.Character.getType: unknown category")
    }.toLong())))
  },
  "u_iswalpha" to wrap2 { x: StgInt, _: VoidInh ->
    UnboxedTuple(arrayOf(StgInt(when (Character.getType(x.x.toInt()).toByte()) {
      Character.UPPERCASE_LETTER -> 1
      Character.LOWERCASE_LETTER -> 1
      Character.TITLECASE_LETTER -> 1
      Character.MODIFIER_LETTER -> 1
      Character.OTHER_LETTER -> 1
      else -> 0
    }.toLong())))
  },
  "u_iswupper" to wrap2 { x: StgInt, _: VoidInh ->
    UnboxedTuple(arrayOf(StgInt(when (Character.getType(x.x.toInt()).toByte()) {
      Character.UPPERCASE_LETTER -> 1
      Character.TITLECASE_LETTER -> 1
      else -> 0
    }.toLong())))
  },
  "u_iswlower" to wrap2 { x: StgInt, _: VoidInh ->
    UnboxedTuple(arrayOf(StgInt(when (Character.getType(x.x.toInt()).toByte()) {
      Character.LOWERCASE_LETTER -> 1
      else -> 0
    }.toLong())))
  },
  "u_iswalnum" to wrap2 { x: StgInt, _: VoidInh ->
    UnboxedTuple(arrayOf(StgInt(when (Character.getType(x.x.toInt()).toByte()) {
      Character.UPPERCASE_LETTER -> 1
      Character.LOWERCASE_LETTER -> 1
      Character.TITLECASE_LETTER -> 1
      Character.MODIFIER_LETTER -> 1
      Character.OTHER_LETTER -> 1
      Character.OTHER_NUMBER -> 1
      Character.DECIMAL_DIGIT_NUMBER -> 1
      Character.LETTER_NUMBER -> 1
      else -> 0
    }.toLong())))
  }
)


// lots of foreign calls need to be boundaries cause truffle doesn't like lots of the java stdlib
inline fun <reified X, reified Y : Any> wrap1Boundary(crossinline f: (X) -> Y): () -> StgPrimOp = {
  object : StgPrimOp1() { @CompilerDirectives.TruffleBoundary override fun execute(x: Any): Any = f((x as? X)!!) }
}
inline fun <reified X, reified Y, reified Z : Any> wrap2Boundary(crossinline f: (X, Y) -> Z): () -> StgPrimOp = {
  object : StgPrimOp2() { @CompilerDirectives.TruffleBoundary override fun execute(x: Any, y: Any): Any = f((x as? X)!!, (y as? Y)!!) }
}
inline fun <reified X, reified Y, reified Z, reified W : Any> wrap3Boundary(crossinline f: (X, Y, Z) -> W): () -> StgPrimOp = {
  object : StgPrimOp3() { @CompilerDirectives.TruffleBoundary override fun execute(x: Any, y: Any, z: Any): Any = f((x as? X)!!, (y as? Y)!!, (z as? Z)!!) }
}
inline fun <reified X, reified Y, reified Z, reified W : Any, reified A> wrap4Boundary(crossinline f: (X, Y, Z, A) -> W): () -> StgPrimOp = {
  object : StgPrimOp4() { @CompilerDirectives.TruffleBoundary override fun execute(x: Any, y: Any, z: Any, a: Any): Any = f((x as? X)!!, (y as? Y)!!, (z as? Z)!!, (a as? A)!!) }
}
