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
      if (seenThunkValue) {
        val v = fn.value_
        if (v === null) {
          if (!seenThunkClosure) {
            invalidate(); seenThunkClosure = true
            reportPolymorphicSpecialize()
          }
          val c = fn.expectClosure()
          fn.clos = null
          val x = thunkDispatch.execute(frame, c, arrayOf())
          fn.value_ = x
          x
        } else { v }
      } else {
        val c = fn.clos
        if (c === null) {
          invalidate(); seenThunkValue = true
          fn.expectValue()
        } else {
          fn.clos = null
          val x = thunkDispatch.execute(frame, c, arrayOf())
          fn.value_ = x
          x
        }
      }
    } else fn

    if (f !is Closure) {
      if (argsSize == 0) { return f }
      panic("CallWhnf: attempt to apply a non-closure $f to arguments")
    }
    return dispatch.execute(frame, f, ys)
  }
}

// TODO: dispatch on closure equality for static (no env or pap) closures?
// (would need to statically allocate them)
// TODO: param for limit of recursive occurences?
// TODO: dispatch by arity first?
// TODO: can we have the same callTarget at different arities? if so, should/can we share the DirectCallNode?
// sharing the directcallnode might merge control flow or something?
//@ReportPolymorphism
abstract class DispatchClosure(@JvmField val argsSize: Int, val tail_call: Boolean) : Node() {
  // pre: ys.size == argsSize
  abstract fun execute(frame: VirtualFrame, fn: Closure, ys: Array<Any>): Any


  @Specialization(guards = [
    "fn.arity == argsSize",
    "fn.callTarget == cachedCallTarget"
  ], limit = "3")
  fun callDirect(frame: VirtualFrame, fn: Closure, ys: Array<Any>?,
                 @Cached("fn.callTarget") cachedCallTarget: RootCallTarget,
                 // determined by fn.callTarget & fn.arity
                 @Cached("fn.papArgs.length") papSize: Int,
                 @Cached("create(cachedCallTarget)") callerNode: DirectCallerNode
                 ): Any? {
    val args = appendLSkip(1, fn.papArgs, papSize, ys, argsSize)
    // TODO: figure out how to avoid TailCallException if inlining
    return callerNode.call(frame, args, tail_call)
  }

  @Specialization(guards = [
    // TODO: can/should this be arity < argsSize?
    "fn.arity < argsSize",
    "fn.arity == arity",
    "fn.callTarget == cachedCallTarget"
  ], limit = "3")
  fun callDirectOverapplied(frame: VirtualFrame, fn: Closure, ys: Array<Any>,
                            @Cached("fn.arity") arity: Int,
                            @Cached("fn.callTarget") cachedCallTarget: RootCallTarget,
                            // determined by fn.callTarget & fn.arity
                            @Cached("fn.papArgs.length") papSize: Int,
                            @Cached("create(cachedCallTarget)") callerNode: DirectCallerNode,
                            @Cached("createMinusTail(argsSize, arity)") dispatch: DispatchClosure): Any? {
    val args = appendLSkip(1, fn.papArgs, papSize, ys, arity)
    val y = callerNode.call(frame, args, false)
    val zs = ys.copyOfRange(arity, argsSize)
    return dispatch.execute(frame, y as Closure, zs)
  }

  @Specialization(guards = ["fn.arity > argsSize"])
  fun callUnderapplied(fn: Closure, ys: Array<Any>): Any? {
    return fn.pap(ys)
  }

  // TODO: should callIndirect & callIndirectOverapplied be merged?

  // replaces => delete callDirect once more than 3 variants
  // TODO: is replaces the right choice?
//  @Specialization(guards = ["fn.arity == argsSize"], replaces = ["callDirect"])
  @Specialization(guards = ["fn.arity == argsSize"])
  @ReportPolymorphism.Megamorphic
  fun callIndirect(frame: VirtualFrame, fn: Closure, ys: Array<Any>,
                   @Cached("create()") callerNode: IndirectCallerNode): Any? {
    val args = appendLSkip(1, fn.papArgs, fn.papArgs.size, ys, argsSize)
    return callerNode.call(frame, fn.callTarget, args, tail_call)
  }

  @Specialization(guards = [
    "fn.arity < argsSize",
    "arity == fn.arity"
  ])
//  ], replaces = ["callDirectOverapplied"])
  @ReportPolymorphism.Megamorphic
  fun callIndirectOverapplied(frame: VirtualFrame, fn: Closure, ys: Array<Any>,
                              @Cached("fn.arity") arity: Int,
                              @Cached("create()") callerNode: IndirectCallerNode,
                              @Cached("createMinusTail(argsSize, arity)") dispatch: DispatchClosure): Any? {
    val xs = ys.copyOf(fn.arity) as Array<Any>
    val zs = ys.copyOfRange(fn.arity, ys.size)
    val args = appendLSkip(1, fn.papArgs, fn.papArgs.size, xs, arity)
    val y = callerNode.call(frame, fn.callTarget, args, false)
    return dispatch.execute(frame, y as Closure, zs)
  }

  fun createMinusTail(x: Int, y: Int): DispatchClosure = DispatchClosureNodeGen.create(x - y, tail_call)
}