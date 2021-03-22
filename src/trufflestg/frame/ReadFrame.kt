package trufflestg.frame

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.dsl.Cached
import com.oracle.truffle.api.dsl.Fallback
import com.oracle.truffle.api.dsl.ReportPolymorphism
import com.oracle.truffle.api.dsl.Specialization
import com.oracle.truffle.api.frame.FrameSlot
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.ExplodeLoop
import com.oracle.truffle.api.nodes.Node

abstract class ReadFrame(
  @CompilerDirectives.CompilationFinal(dimensions = 1) val slots: Array<Pair<FrameSlot, Int>>
) : Node() {
  abstract fun execute(frame: VirtualFrame, o: Any?)

  @Specialization(guards = ["c != null", "o.getClass() == c"], limit = "9")
  @ExplodeLoop
  fun doCached(frame: VirtualFrame, o: Any?, @Cached("getInterfaceType(o)") c: Class<out DataFrame?>?) {
    val fr = CompilerDirectives.castExact(o, c)!!
    for ((slot, ix) in slots) frame.setObject(slot, fr.getValue(ix))
  }

  @Specialization
  @ExplodeLoop
  @ReportPolymorphism.Megamorphic
  fun doSlow(frame: VirtualFrame, o: Any?) {
    val fr = o as DataFrame
    for ((slot, ix) in slots) frame.setObject(slot, fr.getValue(ix))
  }

  fun getInterfaceType(o: Any): Class<out DataFrame?>? =
    if (o is DataFrame) {
      (o as DataFrame).javaClass
    } else null
}


