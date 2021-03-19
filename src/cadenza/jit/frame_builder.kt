package cadenza.jit

import cadenza.data.DataTypes
import com.oracle.truffle.api.dsl.Specialization
import com.oracle.truffle.api.dsl.TypeSystemReference
import com.oracle.truffle.api.frame.FrameSlot
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.nodes.NodeInfo

val noFrameBuilders = arrayOf<FrameBuilder>() // can't make const because kotlin is silly


// this copies information from the VirtualFrame frame into a materialized frame
@TypeSystemReference(DataTypes::class)
@NodeInfo(shortName = "FrameBuilder")
abstract class FrameBuilder(
  private val slot: FrameSlot,
  @field:Child var rhs: Code
) : Node() {

  fun build(frame: VirtualFrame, oldFrame: VirtualFrame) {
    execute(frame, 0, oldFrame)
  }

  abstract fun execute(frame: VirtualFrame, hack: Int, oldFrame: VirtualFrame): Any

  @Specialization
  @Suppress("unused")
  internal fun buildObject(frame: VirtualFrame, @Suppress("UNUSED_PARAMETER") _hack: Int, oldFrame: VirtualFrame): Any? {
    val result = rhs.execute(oldFrame)
    frame.setObject(slot, result)
    return result
  }

  override fun isAdoptable() = false
}

fun put(slot: FrameSlot, value: Code): FrameBuilder = FrameBuilderNodeGen.create(slot, value)