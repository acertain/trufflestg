package trufflestg.data

import com.oracle.truffle.api.CallTarget
import trufflestg.array_utils.*
import trufflestg.frame.DataFrame
import trufflestg.jit.CallUtils
import trufflestg.jit.ClosureRootNode
import trufflestg.panic
import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.frame.MaterializedFrame
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.interop.ArityException
import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.interop.TruffleObject
import com.oracle.truffle.api.interop.UnsupportedTypeException
import com.oracle.truffle.api.library.ExportLibrary
import com.oracle.truffle.api.library.ExportMessage
import com.oracle.truffle.api.nodes.ExplodeLoop
import trufflestg.jit.IndirectCallerNode


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
}

// a statically allocated empty array for closures without args
val emptyEnv: Array<Any> = arrayOf()

@CompilerDirectives.ValueType
abstract class Closure (
  @JvmField val callTarget: RootCallTarget,
  // args left
  @JvmField val arity: Int
  ) : TruffleObject {
  val rootNode: ClosureRootNode
    get() = callTarget.rootNode as ClosureRootNode
  abstract fun papSize(): Int
  abstract fun papArgs(): Array<Any>

  abstract fun pap_1(x: Any): Closure
  abstract fun pap_2(x: Any, y: Any): Closure
  abstract fun pap_3(x: Any, y: Any, z: Any): Closure
  abstract fun pap_4(a: Any, b: Any, c: Any, d: Any): Closure
  abstract fun pap_5(a: Any, b: Any, c: Any, d: Any, e: Any): Closure
  abstract fun pap_6(a: Any, b: Any, c: Any, d: Any, e: Any, f: Any): Closure
  abstract fun pap_7(a: Any, b: Any, c: Any, d: Any, e: Any, f: Any, g: Any): Closure
  abstract fun pap_8(a: Any, b: Any, c: Any, d: Any, e: Any, f: Any, g: Any, h: Any): Closure
  abstract fun pap_generic(ys: Array<Any>): Closure
}

// saturated calls
fun IndirectCallerNode.call_n(mask: Long, callTarget: RootCallTarget, fn: Closure, n: Int, args: Array<Any>): Any? {
//  assert(args.size == n)
//  assert(fn.arity == n)
//  assert(n + fn.papSize() == (fn.callTarget.rootNode as ClosureRootNode).arity)
  return when (n) {
    0 -> call_0(mask, callTarget, fn)
    1 -> call_1(mask, callTarget, fn, args[0])
    else -> call_generic(mask, callTarget, fn, args)
  }
}

// TODO: make these TruffleBoundary?
// TODO: ideally these'd be methods on Closure but idk how IndirectCallNode interacts w/ that
// actually for now the methods can just prep args array
private fun IndirectCallerNode.call_0(mask: Long, callTarget: RootCallTarget, fn: Closure): Any? = call(mask, callTarget, cons(null, fn.papArgs()))
private fun IndirectCallerNode.call_1(mask: Long, callTarget: RootCallTarget, fn: Closure, x: Any): Any? = call(mask, callTarget, appendLSkip(1, fn.papArgs(), fn.papSize(), arrayOf(x), fn.arity))
private fun IndirectCallerNode.call_generic(mask: Long, callTarget: RootCallTarget, fn: Closure, args: Array<Any>): Any? = call(mask, callTarget, appendLSkip(1, fn.papArgs(), fn.papSize(), args, fn.arity))

fun mkClosure(fn: RootCallTarget, arity: Int, args: Array<Any>): Closure = when (args.size) {
  0 -> Closure_0(fn, arity)
  1 -> Closure_1(fn, arity, args[0])
  2 -> Closure_2(fn, arity, args[0], args[1])
  3 -> Closure_3(fn, arity, args[0], args[1], args[2])
  4 -> Closure_4(fn, arity, args[0], args[1], args[2], args[3])
  5 -> Closure_5(fn, arity, args[0], args[1], args[2], args[3], args[4])
  else -> Closure_generic(fn, arity, args)
}

// TODO: maybe also do an inline cache on fn's klass?
// pre: n < fn.arity, n == args.size
fun pap(fn: Closure, n: Int, args: Array<Any>): Closure = when(n) {
  0 -> fn
  1 -> fn.pap_1(args[0])
  2 -> fn.pap_2(args[0], args[1])
  3 -> fn.pap_3(args[0], args[1], args[2])
  4 -> fn.pap_4(args[0], args[1], args[2], args[3])
  5 -> fn.pap_5(args[0], args[1], args[2], args[3], args[4])
  6 -> fn.pap_6(args[0], args[1], args[2], args[3], args[4], args[5])
  7 -> fn.pap_7(args[0], args[1], args[2], args[3], args[4], args[5], args[6])
  8 -> fn.pap_8(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7])
  else -> fn.pap_generic(args)
}


@CompilerDirectives.ValueType
class Closure_generic(
  callTarget: RootCallTarget,
  arity: Int,
  @JvmField @CompilerDirectives.CompilationFinal(dimensions = 1) val papArgs: Array<Any>
  ) : Closure(callTarget, arity) {
  override fun papSize(): Int = papArgs.size
  override fun papArgs(): Array<Any> = papArgs

  override fun pap_1(x: Any): Closure = mkClosure(callTarget, arity - 1, append(papArgs(), arrayOf(x)))
  override fun pap_2(x: Any, y: Any): Closure = mkClosure(callTarget, arity - 2, append(papArgs(), arrayOf(x,y)))
  override fun pap_3(x: Any, y: Any, z: Any): Closure = mkClosure(callTarget, arity - 3, append(papArgs(), arrayOf(x,y,z)))
  override fun pap_4(a: Any, b: Any, c: Any, d: Any): Closure = mkClosure(callTarget, arity - 4, append(papArgs(), arrayOf(a,b,c,d)))
  override fun pap_5(a: Any, b: Any, c: Any, d: Any, e: Any): Closure = mkClosure(callTarget, arity - 5, append(papArgs(), arrayOf(a,b,c,d,e)))
  override fun pap_6(a: Any, b: Any, c: Any, d: Any, e: Any, f: Any): Closure = mkClosure(callTarget, arity - 6, append(papArgs(), arrayOf(a,b,c,d,e,f)))
  override fun pap_7(a: Any, b: Any, c: Any, d: Any, e: Any, f: Any, g: Any): Closure = mkClosure(callTarget, arity - 7, append(papArgs(), arrayOf(a,b,c,d,e,f,g)))
  override fun pap_8(a: Any, b: Any, c: Any, d: Any, e: Any, f: Any, g: Any, h: Any): Closure = mkClosure(callTarget, arity - 8, append(papArgs(), arrayOf(a,b,c,d,e,f,g,h)))
  override fun pap_generic(ys: Array<Any>): Closure = mkClosure(callTarget, arity - ys.size, append(papArgs(), ys))
}

@CompilerDirectives.ValueType
class Closure_0(
  callTarget: RootCallTarget,
  arity: Int
) : Closure(callTarget, arity) {
  override fun papSize(): Int = 0
  override fun papArgs(): Array<Any> = emptyEnv

  override fun pap_1(x: Any): Closure = mkClosure(callTarget, arity - 1, append(papArgs(), arrayOf(x)))
  override fun pap_2(x: Any, y: Any): Closure = mkClosure(callTarget, arity - 2, append(papArgs(), arrayOf(x,y)))
  override fun pap_3(x: Any, y: Any, z: Any): Closure = mkClosure(callTarget, arity - 3, append(papArgs(), arrayOf(x,y,z)))
  override fun pap_4(a: Any, b: Any, c: Any, d: Any): Closure = mkClosure(callTarget, arity - 4, append(papArgs(), arrayOf(a,b,c,d)))
  override fun pap_5(a: Any, b: Any, c: Any, d: Any, e: Any): Closure = mkClosure(callTarget, arity - 5, append(papArgs(), arrayOf(a,b,c,d,e)))
  override fun pap_6(a: Any, b: Any, c: Any, d: Any, e: Any, f: Any): Closure = mkClosure(callTarget, arity - 6, append(papArgs(), arrayOf(a,b,c,d,e,f)))
  override fun pap_7(a: Any, b: Any, c: Any, d: Any, e: Any, f: Any, g: Any): Closure = mkClosure(callTarget, arity - 7, append(papArgs(), arrayOf(a,b,c,d,e,f,g)))
  override fun pap_8(a: Any, b: Any, c: Any, d: Any, e: Any, f: Any, g: Any, h: Any): Closure = mkClosure(callTarget, arity - 8, append(papArgs(), arrayOf(a,b,c,d,e,f,g,h)))
  override fun pap_generic(ys: Array<Any>): Closure = mkClosure(callTarget, arity - ys.size, append(papArgs(), ys))
}


@CompilerDirectives.ValueType
class Closure_1(
  callTarget: RootCallTarget,
  arity: Int,
  val x: Any
) : Closure(callTarget, arity) {
  override fun papSize(): Int = 1
  override fun papArgs(): Array<Any> = arrayOf(x)

  override fun pap_1(x: Any): Closure = mkClosure(callTarget, arity - 1, append(papArgs(), arrayOf(x)))
  override fun pap_2(x: Any, y: Any): Closure = mkClosure(callTarget, arity - 2, append(papArgs(), arrayOf(x,y)))
  override fun pap_3(x: Any, y: Any, z: Any): Closure = mkClosure(callTarget, arity - 3, append(papArgs(), arrayOf(x,y,z)))
  override fun pap_4(a: Any, b: Any, c: Any, d: Any): Closure = mkClosure(callTarget, arity - 4, append(papArgs(), arrayOf(a,b,c,d)))
  override fun pap_5(a: Any, b: Any, c: Any, d: Any, e: Any): Closure = mkClosure(callTarget, arity - 5, append(papArgs(), arrayOf(a,b,c,d,e)))
  override fun pap_6(a: Any, b: Any, c: Any, d: Any, e: Any, f: Any): Closure = mkClosure(callTarget, arity - 6, append(papArgs(), arrayOf(a,b,c,d,e,f)))
  override fun pap_7(a: Any, b: Any, c: Any, d: Any, e: Any, f: Any, g: Any): Closure = mkClosure(callTarget, arity - 7, append(papArgs(), arrayOf(a,b,c,d,e,f,g)))
  override fun pap_8(a: Any, b: Any, c: Any, d: Any, e: Any, f: Any, g: Any, h: Any): Closure = mkClosure(callTarget, arity - 8, append(papArgs(), arrayOf(a,b,c,d,e,f,g,h)))
  override fun pap_generic(ys: Array<Any>): Closure = mkClosure(callTarget, arity - ys.size, append(papArgs(), ys))
}

@CompilerDirectives.ValueType
class Closure_2(
  callTarget: RootCallTarget,
  arity: Int,
  val x: Any,
  val y: Any
) : Closure(callTarget, arity) {
  override fun papSize(): Int = 2
  override fun papArgs(): Array<Any> = arrayOf(x, y)

  override fun pap_1(x: Any): Closure = mkClosure(callTarget, arity - 1, append(papArgs(), arrayOf(x)))
  override fun pap_2(x: Any, y: Any): Closure = mkClosure(callTarget, arity - 2, append(papArgs(), arrayOf(x,y)))
  override fun pap_3(x: Any, y: Any, z: Any): Closure = mkClosure(callTarget, arity - 3, append(papArgs(), arrayOf(x,y,z)))
  override fun pap_4(a: Any, b: Any, c: Any, d: Any): Closure = mkClosure(callTarget, arity - 4, append(papArgs(), arrayOf(a,b,c,d)))
  override fun pap_5(a: Any, b: Any, c: Any, d: Any, e: Any): Closure = mkClosure(callTarget, arity - 5, append(papArgs(), arrayOf(a,b,c,d,e)))
  override fun pap_6(a: Any, b: Any, c: Any, d: Any, e: Any, f: Any): Closure = mkClosure(callTarget, arity - 6, append(papArgs(), arrayOf(a,b,c,d,e,f)))
  override fun pap_7(a: Any, b: Any, c: Any, d: Any, e: Any, f: Any, g: Any): Closure = mkClosure(callTarget, arity - 7, append(papArgs(), arrayOf(a,b,c,d,e,f,g)))
  override fun pap_8(a: Any, b: Any, c: Any, d: Any, e: Any, f: Any, g: Any, h: Any): Closure = mkClosure(callTarget, arity - 8, append(papArgs(), arrayOf(a,b,c,d,e,f,g,h)))
  override fun pap_generic(ys: Array<Any>): Closure = mkClosure(callTarget, arity - ys.size, append(papArgs(), ys))
}


@CompilerDirectives.ValueType
class Closure_3(
  callTarget: RootCallTarget,
  arity: Int,
  val x: Any,
  val y: Any,
  val z: Any
) : Closure(callTarget, arity) {
  override fun papSize(): Int = 3
  override fun papArgs(): Array<Any> = arrayOf(x, y, z)

  override fun pap_1(x: Any): Closure = mkClosure(callTarget, arity - 1, append(papArgs(), arrayOf(x)))
  override fun pap_2(x: Any, y: Any): Closure = mkClosure(callTarget, arity - 2, append(papArgs(), arrayOf(x,y)))
  override fun pap_3(x: Any, y: Any, z: Any): Closure = mkClosure(callTarget, arity - 3, append(papArgs(), arrayOf(x,y,z)))
  override fun pap_4(a: Any, b: Any, c: Any, d: Any): Closure = mkClosure(callTarget, arity - 4, append(papArgs(), arrayOf(a,b,c,d)))
  override fun pap_5(a: Any, b: Any, c: Any, d: Any, e: Any): Closure = mkClosure(callTarget, arity - 5, append(papArgs(), arrayOf(a,b,c,d,e)))
  override fun pap_6(a: Any, b: Any, c: Any, d: Any, e: Any, f: Any): Closure = mkClosure(callTarget, arity - 6, append(papArgs(), arrayOf(a,b,c,d,e,f)))
  override fun pap_7(a: Any, b: Any, c: Any, d: Any, e: Any, f: Any, g: Any): Closure = mkClosure(callTarget, arity - 7, append(papArgs(), arrayOf(a,b,c,d,e,f,g)))
  override fun pap_8(a: Any, b: Any, c: Any, d: Any, e: Any, f: Any, g: Any, h: Any): Closure = mkClosure(callTarget, arity - 8, append(papArgs(), arrayOf(a,b,c,d,e,f,g,h)))
  override fun pap_generic(ys: Array<Any>): Closure = mkClosure(callTarget, arity - ys.size, append(papArgs(), ys))
}

@CompilerDirectives.ValueType
class Closure_4(
  callTarget: RootCallTarget,
  arity: Int,
  val x: Any,
  val y: Any,
  val z: Any,
  val w: Any
) : Closure(callTarget, arity) {
  override fun papSize(): Int = 4
  override fun papArgs(): Array<Any> = arrayOf(x, y, z, w)

  override fun pap_1(x: Any): Closure = mkClosure(callTarget, arity - 1, append(papArgs(), arrayOf(x)))
  override fun pap_2(x: Any, y: Any): Closure = mkClosure(callTarget, arity - 2, append(papArgs(), arrayOf(x,y)))
  override fun pap_3(x: Any, y: Any, z: Any): Closure = mkClosure(callTarget, arity - 3, append(papArgs(), arrayOf(x,y,z)))
  override fun pap_4(a: Any, b: Any, c: Any, d: Any): Closure = mkClosure(callTarget, arity - 4, append(papArgs(), arrayOf(a,b,c,d)))
  override fun pap_5(a: Any, b: Any, c: Any, d: Any, e: Any): Closure = mkClosure(callTarget, arity - 5, append(papArgs(), arrayOf(a,b,c,d,e)))
  override fun pap_6(a: Any, b: Any, c: Any, d: Any, e: Any, f: Any): Closure = mkClosure(callTarget, arity - 6, append(papArgs(), arrayOf(a,b,c,d,e,f)))
  override fun pap_7(a: Any, b: Any, c: Any, d: Any, e: Any, f: Any, g: Any): Closure = mkClosure(callTarget, arity - 7, append(papArgs(), arrayOf(a,b,c,d,e,f,g)))
  override fun pap_8(a: Any, b: Any, c: Any, d: Any, e: Any, f: Any, g: Any, h: Any): Closure = mkClosure(callTarget, arity - 8, append(papArgs(), arrayOf(a,b,c,d,e,f,g,h)))
  override fun pap_generic(ys: Array<Any>): Closure = mkClosure(callTarget, arity - ys.size, append(papArgs(), ys))
}

@CompilerDirectives.ValueType
class Closure_5(
  callTarget: RootCallTarget,
  arity: Int,
  val x: Any,
  val y: Any,
  val z: Any,
  val w: Any,
  val a: Any
) : Closure(callTarget, arity) {
  override fun papSize(): Int = 5
  override fun papArgs(): Array<Any> = arrayOf(x, y, z, w, a)

  override fun pap_1(x: Any): Closure = mkClosure(callTarget, arity - 1, append(papArgs(), arrayOf(x)))
  override fun pap_2(x: Any, y: Any): Closure = mkClosure(callTarget, arity - 2, append(papArgs(), arrayOf(x,y)))
  override fun pap_3(x: Any, y: Any, z: Any): Closure = mkClosure(callTarget, arity - 3, append(papArgs(), arrayOf(x,y,z)))
  override fun pap_4(a: Any, b: Any, c: Any, d: Any): Closure = mkClosure(callTarget, arity - 4, append(papArgs(), arrayOf(a,b,c,d)))
  override fun pap_5(a: Any, b: Any, c: Any, d: Any, e: Any): Closure = mkClosure(callTarget, arity - 5, append(papArgs(), arrayOf(a,b,c,d,e)))
  override fun pap_6(a: Any, b: Any, c: Any, d: Any, e: Any, f: Any): Closure = mkClosure(callTarget, arity - 6, append(papArgs(), arrayOf(a,b,c,d,e,f)))
  override fun pap_7(a: Any, b: Any, c: Any, d: Any, e: Any, f: Any, g: Any): Closure = mkClosure(callTarget, arity - 7, append(papArgs(), arrayOf(a,b,c,d,e,f,g)))
  override fun pap_8(a: Any, b: Any, c: Any, d: Any, e: Any, f: Any, g: Any, h: Any): Closure = mkClosure(callTarget, arity - 8, append(papArgs(), arrayOf(a,b,c,d,e,f,g,h)))
  override fun pap_generic(ys: Array<Any>): Closure = mkClosure(callTarget, arity - ys.size, append(papArgs(), ys))
}
