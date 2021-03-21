package cadenza.stg

import cadenza.Language
import com.oracle.truffle.api.CallTarget
import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.dsl.Specialization
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.ExplodeLoop
import com.oracle.truffle.api.nodes.RootNode
import kotlin.reflect.KClass
import kotlin.reflect.KFunction


class Memoize<K, R : Any, A : Any, B : Any>(val f: K)
  where K : (A, B) -> R, K : KFunction<R>  {
  private val rootNode = MemoizeRootNode(this)
  @CompilerDirectives.CompilationFinal var klass: Class<*>? = null

  operator fun invoke(x: A, y: B): R {
    return rootNode.callerNode.call(x, y) as R
  }
}

class MemoizeRootNode<K, R : Any, A : Any, B : Any>(val m: Memoize<K, R, A, B>) : RootNode(null)
  where K : (A, B) -> R, K : KFunction<R> {
  val calltarget: CallTarget = Truffle.getRuntime().createCallTarget(this)

  @CompilerDirectives.CompilationFinal(dimensions = 1) var argValues: Array<Any> = arrayOf()

  @field:Child var callerNode = DirectCallNode.create(calltarget)

  @ExplodeLoop
  override fun execute(frame: VirtualFrame): Any? {
    val x = frame.arguments[0]
    val y = frame.arguments[1]
    for (x2 in argValues) {
      if (x == x2) {
        return m.f(x2 as A, y as B)
      }
    }
    CompilerDirectives.transferToInterpreterAndInvalidate()
    argValues += x
    return m.f(x as A, y as B)
  }
}


