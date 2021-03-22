package trufflestg.data

import trufflestg.array_utils.*
import trufflestg.frame.DataFrame
import trufflestg.jit.CallUtils
import trufflestg.jit.ClosureRootNode
import trufflestg.panic
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

// TODO: selector thunks
class Thunk(
  @JvmField var clos: Closure?,
  @JvmField var value_: Any?
) {
  fun expectValue(): Any {
    val v = value_
    if (v == null) {
      CompilerDirectives.transferToInterpreter()
      if (clos != null) { panic("Thunk.expectValue() but it's not evaluated") }
      // TODO: threading?
      else { panic("Thunk.expectValue() but evaluation already in progress (infinite loop? bad letrec?)") }
    }
    return v
  }
  fun expectClosure(): Closure {
    val c = clos
    if (c == null) {
      CompilerDirectives.transferToInterpreter()
      if (value_ != null) { panic("Thunk.expectClosure() but it's already evaluated") }
      else { panic("Thunk.expectClosure() but evaluation already in progress (infinite loop? bad letrec?)") }
    }
    return c
  }

  fun whnf(): Any {
    val cl = clos ?: return expectValue()
    clos = null
    var x = cl.call()
    // FIXME: this shouldn't be possible
    if (x is Thunk) { x = x.whnf() }
    value_ = x
    return x
  }
}

// a statically allocated empty array for closures without args
val emptyEnv: Array<Any> = arrayOf()

@CompilerDirectives.ValueType
// TODO: i'm using Closure when arity == 0 sometimes, make sure it works
class Closure (
  @JvmField @CompilerDirectives.CompilationFinal(dimensions = 1) val papArgs: Array<Any>,
  // left
  @JvmField val arity: Int,
  @JvmField val callTarget: RootCallTarget
) : TruffleObject {
  val rootNode: ClosureRootNode
    get() = callTarget.rootNode as ClosureRootNode

  init {
    assert(arity >= 0)
    assert(arity + papArgs.size == (callTarget.rootNode as ClosureRootNode).arity)
  }

  override fun equals(other: Any?): Boolean {
    return (
      (other is Closure) &&
      (callTarget == other.callTarget) &&
      (arity == other.arity) &&
      (papArgs.contentEquals(other.papArgs))
    )
  }

  fun call(): Any {
    if (arity != 0) { throw Exception("Closure.call: bad arity") }
    return CallUtils.callTarget(callTarget, arrayOf(0L, *papArgs))
  }

  fun call(args: Array<Any>): Any = when {
    args.size < arity -> pap(args)
    args.size == arity -> pap(args).call()
    else -> (call(take(arity, args)) as Closure).call(drop(arity, args))
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
  fun pap(@Suppress("UNUSED_PARAMETER") arguments: Array<out Any>): Closure {
    return Closure(append(papArgs, arguments), arity - arguments.size, callTarget)
  }
}


