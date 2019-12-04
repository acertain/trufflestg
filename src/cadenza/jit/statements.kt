package cadenza.jit

import cadenza.Loc
import cadenza.data.DataTypes
import cadenza.data.NeutralException
import com.oracle.truffle.api.dsl.Specialization
import com.oracle.truffle.api.dsl.TypeSystemReference
import com.oracle.truffle.api.frame.FrameSlot
import com.oracle.truffle.api.frame.FrameSlotKind
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.instrumentation.*
import com.oracle.truffle.api.nodes.NodeInfo
import com.oracle.truffle.api.nodes.UnexpectedResultException

// two kinds of statements, one is top level, the other is an expression?

// block, print can be done at the top level or be treated as a result of IO whatever
// def is a top level statement

// these will typically be 'IO' actions
@GenerateWrapper
@TypeSystemReference(DataTypes::class)
abstract class Stmt(loc: Loc? = null) : LocatedNode(loc) {
  @GenerateWrapper.OutgoingConverter
  @Suppress("unused")
  internal fun convertOutgoing(@Suppress("UNUSED_PARAMETER") obj: Any): Any? = null
  abstract fun execute(frame: VirtualFrame)
  override fun createWrapper(probe: ProbeNode): InstrumentableNode.WrapperNode = StmtWrapper(this, probe)
  override fun isInstrumentable() = true
  override fun isAdoptable() = true
  override fun hasTag(tag: Class<out Tag>?) = tag == StandardTags.StatementTag::class.java || super.hasTag(tag)

  @NodeInfo(shortName = "Do")
  class Do internal constructor(
    @field:Children internal var body: Array<Stmt>,
    loc: Loc? = null
  ) : Stmt(loc) {
    override fun execute(frame: VirtualFrame) {
      for (stmt in body) stmt.execute(frame)
    }
  }

  //TODO: use a better internal state management system like the generated code would
  @NodeInfo(shortName = "Def")
  abstract class Def(
    private val slot: FrameSlot,
    @field:Child var arg: Code,
    loc: Loc? = null
  ) : Stmt(loc) {

    override fun execute(frame: VirtualFrame) { executeDef(frame) }

    protected abstract fun executeDef(frame: VirtualFrame): Any?

    @Specialization(guards = ["allowsIntegerSlot(frame)"], rewriteOn = [UnexpectedResultException::class])
    @Throws(UnexpectedResultException::class)
    protected fun defInteger(frame: VirtualFrame): Int {
      val result: Int
      try {
        result = arg.executeInteger(frame)
      } catch (e: UnexpectedResultException) {
        frame.setObject(slot, e)
        throw e
      } catch (e: NeutralException) {
        frame.setObject(slot, e.get())
        return 0 // this result is never used, it just exists to trick truffle into letting me specialize
      }
      frame.setInt(slot, result)
      return result
    }

    @Specialization(guards = ["allowsBooleanSlot(frame)"], rewriteOn = [UnexpectedResultException::class])
    @Throws(UnexpectedResultException::class)
    protected fun defBoolean(frame: VirtualFrame): Boolean {
      val result: Boolean
      try {
        result = arg.executeBoolean(frame)
      } catch (e: UnexpectedResultException) {
        frame.setObject(slot, e)
        throw e
      } catch (e: NeutralException) {
        frame.setObject(slot, e.get())
        return false // never used
      }
      frame.setBoolean(slot, result)
      return result
    }

    @Specialization(replaces = ["defInteger", "defBoolean"])
    protected fun defObject(frame: VirtualFrame) =
      frame.setObject(slot, arg.executeAny(frame))

    private fun allowsSlotKind(frame: VirtualFrame, kind: FrameSlotKind): Boolean {
      val currentKind = frame.frameDescriptor.getFrameSlotKind(slot)
      if (currentKind == FrameSlotKind.Illegal) {
        frame.frameDescriptor.setFrameSlotKind(slot, kind)
        return true
      }
      return currentKind == kind
    }

    protected fun allowsBooleanSlot(frame: VirtualFrame) = allowsSlotKind(frame, FrameSlotKind.Boolean)
    protected fun allowsIntegerSlot(frame: VirtualFrame) = allowsSlotKind(frame, FrameSlotKind.Int)
  }

  companion object {
    @Suppress("NOTHING_TO_INLINE","unused")
    inline fun def(slot: FrameSlot, body: Code, loc: Loc? = null): Def {
      return StmtFactory.DefNodeGen.create(slot, body, loc)
    }
  }
}
