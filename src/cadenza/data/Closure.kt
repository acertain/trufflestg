package cadenza.data

import cadenza.frame.DataFrame
import cadenza.jit.CallUtils
import cadenza.jit.ClosureRootNode
import cadenza.panic
import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.frame.MaterializedFrame
import com.oracle.truffle.api.interop.ArityException
import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.interop.TruffleObject
import com.oracle.truffle.api.interop.UnsupportedTypeException
import com.oracle.truffle.api.library.ExportLibrary
import com.oracle.truffle.api.library.ExportMessage
import com.oracle.truffle.api.nodes.ExplodeLoop


// for testing
// TODO: remove
fun whnf(x: Any): Any = when(x) {
  is Thunk -> x.whnf()
  else -> x
}

class Thunk(
  var clos: Closure?,
  var value: Any?
) {
  fun whnf(): Any =
    if (clos == null) {
      value ?: panic("Thunk: all null (bad LetRec?)")
    } else {
      var x = clos!!.call()
      // FIXME: this shouldn't be possible
      if (x is Thunk) {
        x = x.whnf()
      }
      clos = null
      value = x
      x
    }
}


// TODO: consider storing env in papArgs, to make indirect calls faster
// (don't need to branch on env + pap, just pap)
// & store flag if it has an env & read it from papArgs?
@CompilerDirectives.ValueType
// TODO: i'm using Closure when arity == 0 sometimes, make sure it works
class Closure (
  @JvmField val env: MaterializedFrame? = null,
  @JvmField @CompilerDirectives.CompilationFinal(dimensions = 1) val papArgs: Array<Any?>,
  // left
  @JvmField val arity: Int,
  @JvmField val callTarget: RootCallTarget
) : TruffleObject {
  val rootNode: ClosureRootNode
    get() = callTarget.rootNode as ClosureRootNode

  init {
    assert(env != null == (callTarget.rootNode as ClosureRootNode).isSuperCombinator()) { "calling convention mismatch" }
    assert(arity + papArgs.size == (callTarget.rootNode as ClosureRootNode).arity)
  }

  override fun equals(other: Any?): Boolean {
    return (
      (other is Closure) &&
      (callTarget == other.callTarget) &&
      (arity == other.arity) &&
      (papArgs.contentEquals(other.papArgs)) &&
      (env == other.env)
    )
  }

  // returns a Closure or a Thunk
  fun apply(arguments: Array<Any?>): Any = when {
    arity == arguments.size -> Thunk(pap(arguments), null)
    arity < arguments.size -> pap(arguments)
    else -> TODO()
  }

  fun call(): Any {
    if (arity != 0) { throw Exception("Closure.call: bad arity") }
    val args = if (env != null) arrayOf(0L, env, *papArgs) else arrayOf(0L, *papArgs)
    return CallUtils.callTarget(callTarget, args)
  }

  fun call(args: Array<Any?>): Any {
    val x = apply(args)
    val r = (x as Thunk).whnf()
    if (r is Closure) { panic("Closure.call whnf returned a closure?") }
    return r
//    return ((x as Thunk).whnf() as Closure).call()
  }

//  @ExportMessage
//  fun isExecutable() = true
//
//  // allow the use of our closures from other polyglot languages
//  @ExportMessage
//  @ExplodeLoop
//  @Throws(ArityException::class, UnsupportedTypeException::class)
//  fun execute(vararg arguments: Any?): Any? {
//    val maxArity = type.arity
//    val len = arguments.size
//    if (len > maxArity) throw ArityException.create(maxArity, len)
//    arguments.fold(type) { t, it -> (t as Arr).apply { argument.validate(it) }.result }
//    @Suppress("UNCHECKED_CAST")
//    return call(arguments)
//  }
//
//  // only used for InteropLibrary execute
//  fun call(ys: Array<out Any?>): Any? {
//    // TODO: need to catch TailCallException here
//    // or maybe we should have a special RootNode for InteropLibrary instead of closure?
//    // to deal w/ second level dispatch
//    return when {
//      ys.size < arity -> pap(ys)
//      ys.size == arity -> {
//        val args = if (env != null) consAppend(env, papArgs, ys) else append(papArgs, ys)
//        CallUtils.callTarget(callTarget, args)
//      }
//      else -> {
//        val zs = append(papArgs, ys)
//        val args = if (env != null) consTake(env, arity, zs) else (zs.take(arity).toTypedArray())
//        val g = CallUtils.callTarget(callTarget, args)
//        (g as Closure).call(drop(arity, zs))
//      }
//    }
//  }

  // construct a partial application node, which should check that it is a PAP itself
  @CompilerDirectives.TruffleBoundary
  fun pap(@Suppress("UNUSED_PARAMETER") arguments: Array<out Any?>): Closure {
    val len = arguments.size
    return Closure(env, append(papArgs, arguments), arity - len, callTarget)
  }
}

fun append(xs: Array<out Any?>, ys: Array<out Any?>): Array<Any?> = appendL(xs, xs.size, ys, ys.size)
fun consAppend(x: Any, xs: Array<out Any?>, ys: Array<out Any?>): Array<Any?> = consAppendL(x, xs, xs.size, ys, ys.size)
private fun cons(x: Any, xs: Array<out Any?>): Array<Any?> = consL(x, xs, xs.size)

// kotlin emits null checks in fn preamble for all nullable args
// here it effects dispatch fast path, so xs & ys need to be nullable
fun appendL(xs: Array<out Any?>?, xsSize: Int, ys: Array<out Any?>?, ysSize: Int): Array<Any?> {
  val zs = arrayOfNulls<Any>(xsSize + ysSize)
  System.arraycopy(xs, 0, zs, 0, xsSize)
  System.arraycopy(ys, 0, zs, xsSize, ysSize)
  return zs
}

fun consAppendL(x: Any, xs: Array<out Any?>?, xsSize: Int, ys: Array<out Any?>?, ysSize: Int): Array<Any?> {
  val zs = appendLSkip(1, xs, xsSize, ys, ysSize)
  zs[0] = x
  return zs
}

fun appendLSkip(skip: Int, xs: Array<out Any?>?, xsSize: Int, ys: Array<out Any?>?, ysSize: Int): Array<Any?> {
  val zs = arrayOfNulls<Any>(skip + xsSize + ysSize)
  System.arraycopy(xs, 0, zs, skip, xsSize)
  System.arraycopy(ys, 0, zs, skip + xsSize, ysSize)
  return zs
}

fun consL(x: Any, xs: Array<out Any?>, xsSize: Int): Array<Any?> {
  val ys = arrayOfNulls<Any>(xsSize + 1)
  ys[0] = x
  System.arraycopy(xs, 0, ys, 1, xsSize)
  return ys
}


private fun consTake(x: Any, n: Int, xs: Array<out Any?>): Array<Any?> {
  val ys = arrayOfNulls<Any>(n + 1)
  ys[0] = x
  System.arraycopy(xs, 0, ys, 1, n)
  return ys
}

fun drop(k: Int, xs: Array<out Any?>): Array<Any?> {
  val ys = arrayOfNulls<Any>(xs.size - k)
  System.arraycopy(xs, k, ys, 0, xs.size - k)
  return ys
}

inline fun<reified T> take(k: Int, xs: Array<out T>): Array<T> {
  val ys = arrayOfNulls<T>(k)
  System.arraycopy(xs, 0, ys, 0, k)
  return ys as Array<T>
}


inline fun<S, reified T> map(xs: Array<out S>, f: (x: S) -> T): Array<T> {
  val ys = arrayOfNulls<T>(xs.size)
  xs.forEachIndexed { ix, x -> ys[ix] = f(x) }
  return ys as Array<T>
}

