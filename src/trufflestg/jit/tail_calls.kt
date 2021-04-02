package trufflestg.jit

import com.oracle.truffle.api.*
import com.oracle.truffle.api.frame.*
import com.oracle.truffle.api.nodes.*
import com.oracle.truffle.api.nodes.RepeatingNode.CONTINUE_LOOP_STATUS
import com.oracle.truffle.api.profiles.BranchProfile
import trufflestg.panic
import java.lang.Exception
import java.lang.reflect.Method


// list of ways custom loop node better than builtin
// * if reflection not allowed, can make variant that still compiles w/ interpreted parent
//   & doesn't need to use the parent's FrameWithoutBoxing for state
// * less interpreter overhead
// * less messy not using rootNode's fd


class TailCallException(val fn: RootCallTarget, @CompilerDirectives.CompilationFinal(dimensions = 1) val args: Array<Any>) : ControlFlowException() {}

class TailCheck : Node() {
  @CompilerDirectives.CompilationFinal var root: Node? = null
  private val tailCallProfile: BranchProfile = BranchProfile.create()
  private val unrollProfile: BranchProfile = BranchProfile.create()

  fun tailCheck(mask: Long, fn: RootCallTarget, args: Array<Any>) {
    if (fn.rootNode !is CadenzaRootNode) {
      panic("calling non-canedza rootNode w/ wrong convention!")
    }
    val targetMask = (fn.rootNode as CadenzaRootNode).mask
    if (mask and targetMask == targetMask) {
      tailCallProfile.enter()
      // hit, throw a tail call
      throw TailCallException(fn, args)
    } else {
      unrollProfile.enter()
      args[0] = mask
    }
  }
}

class DirectCallerNode(val callTarget: RootCallTarget, val tail_call: Boolean) : Node() {
  @Child private var callNode: DirectCallNode = DirectCallNode.create(callTarget)
  @Child internal var loop = TailCallLoop()
  @Child var tailCheck = TailCheck()

  private val normalCallProfile = BranchProfile.create()
  private val tailCallProfile = BranchProfile.create()

  fun call(mask: Long, args: Array<Any>): Any {
    // TODO: mb don't need to check tail_call since mask = 0L if not tail?
    return if (tail_call) {
      tailCheck.tailCheck(mask, callTarget, args)
      CallUtils.callDirect(callNode, args)
    } else {
      try {
        args[0] = 0L
        val x = CallUtils.callDirect(callNode, args)
        normalCallProfile.enter()
        x
      } catch (tailCall: TailCallException) {
        tailCallProfile.enter()
        loop.execute(tailCall)
      }
    }
  }

  companion object {
    @JvmStatic fun create(callTarget: RootCallTarget, tail_call: Boolean) = DirectCallerNode(callTarget, tail_call)
  }
}



class IndirectCallerNode(val tail_call: Boolean) : Node() {
  @Child private var callNode: IndirectCallNode = IndirectCallNode.create()
  @Child internal var loop = TailCallLoop()
  @Child var tailCheck = TailCheck()

  private val normalCallProfile = BranchProfile.create()
  private val tailCallProfile = BranchProfile.create()

  fun call(mask: Long, callTarget: RootCallTarget, args: Array<Any>): Any {
    return if (tail_call) {
      tailCheck.tailCheck(mask, callTarget, args)
      CallUtils.callIndirect(callNode, callTarget, args)
    } else {
      try {
        args[0] = 0L
        val x = CallUtils.callIndirect(callNode, callTarget, args)
        normalCallProfile.enter()
        x
      } catch (tailCall: TailCallException) {
        tailCallProfile.enter()
        loop.execute(tailCall)
      }
    }
  }

  companion object {
    @JvmStatic fun create(tail_call: Boolean) = IndirectCallerNode(tail_call)
  }
}


class TailCallLoop() : Node() {
  @Child var loopNode: LoopNode? = null
  @Child var repeatingNode: TailCallRepeatingNode? = null

  fun execute(tailCall: TailCallException): Any {
    if (loopNode == null) {
      CompilerDirectives.transferToInterpreterAndInvalidate()
      repeatingNode = TailCallRepeatingNode(rootNode.frameDescriptor)
      val slots = arrayOf(repeatingNode!!.argsSlot, repeatingNode!!.functionSlot, repeatingNode!!.resultSlot)
      loopNode = createOptimizedLoopNode(repeatingNode!!, slots, slots)
      adoptChildren()
    }
    val frame = Truffle.getRuntime().createVirtualFrame(null, repeatingNode!!.descriptor)
    repeatingNode!!.setNextCall(frame, tailCall.fn, tailCall.args)
    loopNode!!.execute(frame)
    return repeatingNode!!.getResult(frame)
  }
}

// current version copied from
// https://github.com/luna/enso/blob/master/engine/runtime/src/main/java/org/enso/interpreter/node/callable/dispatch/LoopingCallOptimiserNode.java
class TailCallRepeatingNode(val descriptor: FrameDescriptor) : Node(), RepeatingNode {
  val resultSlot = descriptor.findOrAddFrameSlot("<TCO Function>", FrameSlotKind.Object)
  val functionSlot = descriptor.findOrAddFrameSlot("<TCO Result>", FrameSlotKind.Object)
  val argsSlot = descriptor.findOrAddFrameSlot("<TCO Arguments>", FrameSlotKind.Object)
  @Child var dispatchNode: DispatchCallTarget = DispatchCallTargetNodeGen.create()

  fun setNextCall(
    frame: VirtualFrame,
    fn: CallTarget,
    arguments: Array<Any>) {
    frame.setObject(functionSlot, fn)
    frame.setObject(argsSlot, arguments)
  }

  fun getResult(frame: VirtualFrame): Any {
    return FrameUtil.getObjectSafe(frame, resultSlot)
  }

  private fun getNextFunction(frame: VirtualFrame): CallTarget {
    val result = FrameUtil.getObjectSafe(frame, functionSlot) as CallTarget
    frame.setObject(functionSlot, null)
    return result
  }

  private fun getNextArgs(frame: VirtualFrame): Array<Any> {
    val result = FrameUtil.getObjectSafe(frame, argsSlot) as Array<Any>
    frame.setObject(argsSlot, null)
    return result
  }

  override fun executeRepeating(frame: VirtualFrame): Boolean {
    return try {
      val fn = getNextFunction(frame)
      val args = getNextArgs(frame)
      args[0] = 0L
      frame.setObject(resultSlot, dispatchNode.executeDispatch(fn, args))
      false
    } catch (e: TailCallException) {
      setNextCall(frame, e.fn, e.args)
      true
    }
  }
}


class SelfTailCallLoop(
  @field:Child var body: ClosureBody,
  private val closureRoot: ClosureRootNode
): Node() {
  @field:Child var repeatingNode: SelfTailCallRepeatingNode = SelfTailCallRepeatingNode(body, closureRoot)
  // TODO: use frameDescriptor.version (Assumption)?
  // this only works when no slots added after creation & all slots are filled before calling
//  val slots: Array<FrameSlot> = closureRoot.frameDescriptor.slots.toTypedArray()
  @field:Child var loopNode: LoopNode = Truffle.getRuntime().createLoopNode(repeatingNode) //createOptimizedLoopNode(repeatingNode, slots, slots)

  fun execute(frame: VirtualFrame): Any? {
    return loopNode.execute(frame)
  }
}


class SelfTailCallRepeatingNode(
  @field:Child var body: ClosureBody,
  private val closureRoot: ClosureRootNode
): RepeatingNode, Node() {
  override fun executeRepeating(frame: VirtualFrame): Boolean { panic("") }

  override fun executeRepeatingWithValue(frame: VirtualFrame): Any? {
    return try {
      body.execute(frame)
    } catch (e: TailCallException) {
      if (e.fn.rootNode == closureRoot) {
        closureRoot.buildFrame(e.args, frame)
        CONTINUE_LOOP_STATUS
      } else { throw e }
    }
  }
}



class DummyRepeatingNode() : Node(), RepeatingNode {
  override fun executeRepeating(frame: VirtualFrame?): Boolean = false
}

val createOSRLoop: Method? by lazy {
  try {
    val klass: Class<*> = if (TruffleOptions.AOT) {
      Class.forName("org.graalvm.compiler.truffle.runtime.OptimizedOSRLoopNode\$OptimizedDefaultOSRLoopNode")
    } else {
      Truffle.getRuntime().createLoopNode(DummyRepeatingNode())::class.java
    }
    klass.getMethod("createOSRLoop", RepeatingNode::class.java,
      Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
      Array<FrameSlot>::class.java, Array<FrameSlot>::class.java)
  } catch (e: Exception) {
    println("Virtualizing OSR loop node creation failed, falling back to normal LoopNode: $e")
    null
  }
}

// Note: to avoid "java.lang.AssertionError: Frames should never shrink.", you must:
// 1. adopt the resulting LoopNode before executing it
// 2. use the LoopNode's (parent's) rootNode.frameDescriptor as the fd for the frame you pass to LoopNode.execute
// (but only the subset readFrameSlots/writtenFrameSlots will be available in the loop)
fun createOptimizedLoopNode(repeatingNode: RepeatingNode, readFrameSlots: Array<FrameSlot>, writtenFrameSlots: Array<FrameSlot>): LoopNode {
  val loopNode = Truffle.getRuntime().createLoopNode(repeatingNode)
//  if (!OzLanguage.ON_GRAAL) {
//    return loopNode
//  }
  val m = createOSRLoop
  if (m != null) {
    return m.invoke(null, repeatingNode, 3, 100_000, readFrameSlots, writtenFrameSlots) as LoopNode
//    } catch (e: Exception) {}
  }
  return loopNode
}