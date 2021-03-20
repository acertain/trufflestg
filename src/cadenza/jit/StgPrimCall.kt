package cadenza.jit

import cadenza.stg_types.Stg
import com.oracle.truffle.api.frame.VirtualFrame

class StgPrimCall(
  val x: Stg.PrimCall,
  @field:Children val args: Array<Arg>
) : Code(null) {
  override fun execute(frame: VirtualFrame): Any {
    TODO("Not yet implemented")
  }

}

