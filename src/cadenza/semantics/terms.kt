package cadenza.semantics

import cadenza.*
import cadenza.jit.*
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.source.Source

typealias Ctx = Env<Type>

data class CompileInfo(
  val source: Source,
  val language: Language
)

// terms can be checked and inferred. The result is an expression.
abstract class Term {
  @Throws(TypeError::class) open fun check(ctx: Ctx, expectedType: Type): Witness = infer(ctx).match(expectedType)
  @Throws(TypeError::class) abstract fun infer(ctx: Ctx): Witness

  // provides an expression with a given type in a given frame
  abstract class Witness internal constructor(val type: Type) {
    abstract fun compile(ci: CompileInfo, fd: FrameDescriptor): Code
    @Throws(TypeError::class)
    fun match(expectedType: Type): Witness =
      if (type == expectedType) this
      else throw TypeError("type mismatch", type, expectedType)
  }

  companion object {
    @Suppress("unused")
    fun tvar(name: String, loc: Loc? = null): Term = object : Term() {
      @Throws(TypeError::class)
      override fun infer(ctx: Ctx): Witness = object : Witness(ctx.lookup(name)) {
        override fun compile(ci: CompileInfo, fd: FrameDescriptor): Code = Code.`var`(fd.findOrAddFrameSlot(name), loc)
      }
    }

    @Suppress("unused")
    fun tif(cond: Term, thenTerm: Term, elseTerm: Term, loc: Loc? = null): Term = object : Term() {
      @Throws(TypeError::class)
      override fun infer(ctx: Ctx): Witness {
        val condWitness = cond.check(ctx, Type.Bool)
        val thenWitness = thenTerm.infer(ctx)
        val actualType = thenWitness.type
        val elseWitness = elseTerm.check(ctx, actualType)
        return object : Witness(actualType) {
          override fun compile(ci: CompileInfo, fd: FrameDescriptor): Code {
            return Code.If(actualType, condWitness.compile(ci,fd), thenWitness.compile(ci,fd), elseWitness.compile(ci,fd), loc)
          }
        }
      }
    }

    @Suppress("unused")
    fun tapp(trator: Term, vararg trands: Term, loc: Loc? = null): Term = object : Term() {
      @Throws(TypeError::class)
      override fun infer(ctx: Ctx): Witness {
        val wrator = trator.infer(ctx)
        var currentType = wrator.type
        val wrands = trands.map {
          val arr = currentType as Type.Arr? ?: throw TypeError("not a fun type")
          val out = it.check(ctx, arr.argument)
          currentType = arr.result
          return out
        }.toTypedArray<Witness>()
        return object : Witness(currentType) {
          override fun compile(ci: CompileInfo, fd: FrameDescriptor): Code {
            return Code.App(
              wrator.compile(ci,fd),
              wrands.map { it.compile(ci,fd) }.toTypedArray(),
              loc
            )
          }
        }
      }
    }

    @Suppress("UNUSED_PARAMETER","unused")
    fun tlam(names: Array<Pair<Name,Type>>, body: Term, loc: Loc? = null): Term = object : Term() {
      override fun infer(ctx: Ctx): Witness {
        var ctx2 = ctx;
        for ((n,ty) in names) {
          ctx2 = ConsEnv(n, ty, ctx)
        }
        val bodyw = body.infer(ctx2)
        var aty = bodyw.type
        for ((_,ty) in names.reversed()) {
          aty = Type.Arr(ty, aty)
        }
        val arity = names.size
        return object : Witness(aty) {
          // looks at what vars the body adds to it's FrameDescriptor to decide what to capture
          // TODO: maybe should calculate fvs instead?
          override fun compile(ci: CompileInfo, fd: FrameDescriptor): Code {
            val bodyFd = FrameDescriptor()
            val bodyCode = bodyw.compile(ci, bodyFd)
            // used as spec for materialized frame in closure
            val closureFd = FrameDescriptor()
            val closureCaptures: ArrayList<FrameBuilder> = ArrayList();
            val envPreamble: ArrayList<FrameBuilder> = ArrayList();
            val argPreamble: ArrayList<FrameBuilder> = ArrayList();

            val captures = bodyFd.identifiers.any { n -> names.find { it.first == n } == null }

            for (name in bodyFd.identifiers) {
              val bodySlot = bodyFd.findFrameSlot(name)

              val ix = names.indexOfLast {it.first == name}
              if (ix != -1) {
                argPreamble += put(bodySlot,Code.Arg(if (captures) (ix + 1) else ix))
              } else {
                val closureSlot = closureFd.addFrameSlot(name)
                val parentSlot = fd.findOrAddFrameSlot(name)

                closureCaptures += put(closureSlot,Code.`var`(parentSlot))
                envPreamble += put(bodySlot,Code.`var`(closureSlot))
              }
            }

            assert((!captures) || envPreamble.isNotEmpty())

            val bodyBody = ClosureBody(bodyCode)

            // is this the right way to get the TruffleLanguage?
            val rootNode = ClosureRootNode(ci.language, bodyFd, arity,
              envPreamble.toTypedArray(), argPreamble.toTypedArray(), bodyBody, ci.source)

            val callTarget: RootCallTarget = Truffle.getRuntime().createCallTarget(rootNode)

            return Code.lam(closureFd, closureCaptures.toTypedArray(), callTarget, aty)
          }
        }
      }
    }
  }
}