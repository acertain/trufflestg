package trufflestg.jit

import trufflestg.array_utils.appendLSkip
import trufflestg.data.*
import trufflestg.frame.DataFrame
import trufflestg.panic
import com.oracle.truffle.api.CallTarget
import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.dsl.Cached
import com.oracle.truffle.api.dsl.ReportPolymorphism
import com.oracle.truffle.api.dsl.Specialization
import com.oracle.truffle.api.frame.MaterializedFrame
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.*
import com.oracle.truffle.api.profiles.BranchProfile


// expects fully applied & doesn't trampoline
// just an inline cache of DirectCallNodes & IndirectCallNodes
//@ReportPolymorphism
abstract class DispatchCallTarget : Node() {
  abstract fun executeDispatch(callTarget: CallTarget, ys: Array<Any>): Any

  @Specialization(guards = [
    "callTarget == cachedCallTarget"
  ], limit = "3")
  fun callDirect(callTarget: CallTarget, ys: Array<Any?>?,
                 @Cached("callTarget") cachedCallTarget: CallTarget,
                 @Cached("create(cachedCallTarget)") callNode: DirectCallNode): Any? {
    return CallUtils.callDirect(callNode, ys)
  }

  @Specialization
  @ReportPolymorphism.Megamorphic
  fun callIndirect(callTarget: CallTarget, ys: Array<Any?>?,
                   @Cached("create()") callNode: IndirectCallNode): Any? {
    return CallUtils.callIndirect(callNode, callTarget, ys)
  }
}

fun slowpath() = CompilerDirectives.transferToInterpreter()
fun invalidate() = CompilerDirectives.transferToInterpreterAndInvalidate()

// optionally apply some args then make it whnf
class CallWhnf(@JvmField val argsSize: Int, val tail_call: Boolean): Node() {
  @field:Child var thunkDispatch: DispatchClosure = DispatchClosureNodeGen.create(0, false)
  @field:Child var dispatch: DispatchClosure = DispatchClosureNodeGen.create(argsSize, tail_call)

  @CompilerDirectives.CompilationFinal var seenThunkClosure: Boolean = false
  @CompilerDirectives.CompilationFinal var seenThunkValue: Boolean = false
  private val thunkProfile: BranchProfile = BranchProfile.create()

  fun execute(frame: VirtualFrame, fn: Any, ys: Array<Any>): Any {
    if (ys.size != argsSize) { panic("CallWhnf: bad ys") }

    // the code duplication is so that when we have only seen one of evaluated or non-evaluated thunk we only need to do one read
    val f = if (fn is Thunk) {
      thunkProfile.enter()
      // TODO: concurrency (blackholes/synchronization)
      if (!seenThunkClosure) {
        val v = fn.value_
        if (v === null) {
          invalidate(); seenThunkClosure = true; reportPolymorphicSpecialize()
          val c = fn.expectClosure()
          fn.clos = null
          val x = thunkDispatch.run(frame, c, arrayOf())
          fn.value_ = x
          x
        } else { v }
      } else {
        val c = fn.clos
        if (c === null) {
          if (!seenThunkValue) { invalidate(); seenThunkValue = true }
          fn.expectValue()
        } else {
          fn.clos = null
          val x = thunkDispatch.run(frame, c, arrayOf())
          fn.value_ = x
          x
        }
      }
    } else fn

    if (argsSize == 0) {
      if (f !is Closure) return f
    } else {
      // TODO: only check this when assertions enabled?
      if (f !is Closure) panic{"CallWhnf: attempt to apply a non-closure $f to arguments"}
    }
    return dispatch.run(frame, f, ys)
  }
}

// TODO: dispatch on closure equality for static (no env or pap) closures?
// (would need to statically allocate them)
// TODO: param for limit of recursive occurences?
// TODO: dispatch by arity first?
// TODO: can we have the same callTarget at different arities? if so, should/can we share the DirectCallNode?
// sharing the directcallnode might merge control flow or something?
//@ReportPolymorphism
abstract class DispatchClosure(@JvmField val argsSize: Int, @JvmField val tail_call: Boolean) : Node() {
  @CompilerDirectives.CompilationFinal var root: Node? = null

  fun run(frame: VirtualFrame, fn: Closure, ys: Array<Any>): Any {
    val mask = if (tail_call) {
      if (root == null) {
        CompilerDirectives.transferToInterpreterAndInvalidate()
        root = rootNode
      }
      if (root !is ClosureRootNode) {
        panic("DispatchClosure in non-ClosureRootNode!")
      }
      frame.getLong((root as ClosureRootNode).bloomFilterSlot)
    } else 0L
    val ct = fn.callTarget
    return execute(ct, fn, mask, ys)
  }

  private fun runMask(fn: Closure, mask: Long, ys: Array<Any>): Any = execute(fn.callTarget, fn, mask, ys)

  // pre: ys.size == argsSize
  abstract fun execute(callTarget: RootCallTarget, fn: Closure, mask: Long, ys: Array<Any>): Any

  // TODO: in theory i could generate a closure class per calltarget?
  // might be worth a try? mb generic apply could be an abstract method in Closure?
  // TODO: wait does calltarget + argsSize determine cachedKlass?
  @Specialization(guards = [
    "arity == argsSize",
    "callTarget == cachedCallTarget",
    // TODO: use CompilerDirectives.isExact
    "cachedKlass.isInstance(fn)"
  ], limit = "3")
  fun callDirect(callTarget: RootCallTarget, fn: Closure, mask: Long, ys: Array<Any>,
                 @Cached("fn.arity") arity: Int,
                 @Cached("callTarget") cachedCallTarget: RootCallTarget,
                 @Cached("getClosureType(fn)") cachedKlass: Class<out Closure?>,
                 // determined by fn.callTarget & fn.arity
                 @Cached("fn.papSize()") papSize: Int,
                 @Cached("create(cachedCallTarget, tail_call)") callerNode: DirectCallerNode
                 ): Any {
    val fn2 = CompilerDirectives.castExact(fn, cachedKlass)!!
    val args = appendLSkip(1, fn2.papArgs(), papSize, ys, argsSize)
    // TODO: figure out how to avoid TailCallException if inlining
    return callerNode.call(mask, args)
  }

  @Specialization(guards = [
    "arity < argsSize",
    "callTarget == cachedCallTarget",
    "cachedKlass.isInstance(fn)"
  ], limit = "3")
  fun callDirectOverapplied(callTarget: RootCallTarget, fn: Closure, mask: Long, ys: Array<Any>,
                            @Cached("fn.arity") arity: Int,
                            @Cached("callTarget") cachedCallTarget: RootCallTarget,
                            @Cached("getClosureType(fn)") cachedKlass: Class<out Closure?>,
                            // determined by fn.callTarget & fn.arity
                            @Cached("fn.papSize()") papSize: Int,
                            @Cached("create(cachedCallTarget, false)") callerNode: DirectCallerNode,
                            @Cached("createMinusTail(argsSize, arity)") dispatch: DispatchClosure): Any {
    val fn2 = CompilerDirectives.castExact(fn, cachedKlass)!!
    val args = appendLSkip(1, fn2.papArgs(), papSize, ys, arity)
    val y = callerNode.call(0L, args)
    val zs = ys.copyOfRange(arity, argsSize)
    return dispatch.runMask((y as? Closure)!!, mask, zs)
  }

  @Specialization(guards = ["fn.arity > argsSize"])
  fun callUnderapplied(callTarget: RootCallTarget, fn: Closure, mask: Long, ys: Array<Any>,): Any = pap(fn, argsSize, ys)

  // TODO: should callIndirect & callIndirectOverapplied be merged?

  // replaces => delete callDirect once more than 3 variants
  // TODO: is replaces the right choice?
//  @Specialization(guards = ["fn.arity == argsSize"], replaces = ["callDirect"])
  @Specialization(guards = ["fn.arity == argsSize"])
  @ReportPolymorphism.Megamorphic
  fun callIndirect(callTarget: RootCallTarget, fn: Closure, mask: Long, ys: Array<Any>,
                   @Cached("create(tail_call)") callerNode: IndirectCallerNode): Any? {
    return callerNode.call_n(mask, callTarget, fn, argsSize, ys)
  }

  @Specialization(guards = [
    "fn.arity < argsSize",
    "arity == fn.arity"
  ])
//  ], replaces = ["callDirectOverapplied"])
  @ReportPolymorphism.Megamorphic
  fun callIndirectOverapplied(callTarget: RootCallTarget, fn: Closure, mask: Long, ys: Array<Any>,
                              @Cached("fn.arity") arity: Int,
                              @Cached("create(false)") callerNode: IndirectCallerNode,
                              @Cached("createMinusTail(argsSize, arity)") dispatch: DispatchClosure): Any {
    val xs = ys.copyOf(fn.arity) as Array<Any>
    val zs = ys.copyOfRange(fn.arity, ys.size)
    val y = callerNode.call_n(0L, callTarget, fn, argsSize, xs)
    return dispatch.runMask((y as? Closure)!!, mask, zs)
  }

  fun createMinusTail(x: Int, y: Int): DispatchClosure = DispatchClosureNodeGen.create(x - y, tail_call)
  fun getClosureType(o: Any): Class<out Closure?>? =
    if (o is Closure) {
      (o as Closure).javaClass
    } else null
}

