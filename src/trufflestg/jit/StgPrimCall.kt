package trufflestg.jit

import trufflestg.stg.Stg
import com.oracle.truffle.api.frame.VirtualFrame

class StgPrimCall(
  val x: Stg.PrimCall,
  @field:Children val args: Array<Arg>
) : Code(null) {
  override fun execute(frame: VirtualFrame): Any {
    TODO("Not yet implemented")
  }

}

