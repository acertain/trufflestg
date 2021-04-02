package trufflestg.jit

import trufflestg.*
import trufflestg.data.*
import trufflestg.frame.BuildFrame
import trufflestg.frame.BuildFrameNodeGen
import trufflestg.stg.Stg
import trufflestg.stg.Module
import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.dsl.ReportPolymorphism
import com.oracle.truffle.api.dsl.TypeSystemReference
import com.oracle.truffle.api.frame.*
import com.oracle.truffle.api.instrumentation.*
import com.oracle.truffle.api.nodes.*
import com.oracle.truffle.api.profiles.BranchProfile
import com.oracle.truffle.api.source.SourceSection
import trufflestg.array_utils.map
import trufflestg.data.DataTypes

// utility
@Suppress("NOTHING_TO_INLINE")
private inline fun isSuperCombinator(callTarget: RootCallTarget) =
  callTarget.rootNode.let { it is ClosureRootNode && it.isSuperCombinator() }

@GenerateWrapper
@NodeInfo(language = "core", description = "core nodes")
@TypeSystemReference(DataTypes::class)
abstract class Code(val loc: Loc?) : Node(), InstrumentableNode {
  constructor(that: Code) : this(that.loc)

  // should never return null, the Any? is just to get kotlin to insert less null checks
  abstract fun execute(frame: VirtualFrame): Any?

//  override fun getSourceSection(): SourceSection? = loc?.let { rootNode?.sourceSection?.source?.section(it) }
  override fun isInstrumentable() = true

  override fun hasTag(tag: Class<out Tag>?) = tag ==
    StandardTags.ExpressionTag::class.java || tag == StandardTags.StatementTag::class.java
  override fun createWrapper(probe: ProbeNode): InstrumentableNode.WrapperNode = CodeWrapper(this, this, probe)

  @TypeSystemReference(DataTypes::class)
  @NodeInfo(shortName = "App")
  open class App(
    @field:Child var rator: Arg.Var,
    @field:Children val rands: Array<Arg>,
    tail_call: Boolean
  ) : Code(null) {
    @field:Child private var callWhnf: CallWhnf = CallWhnf(rands.size, tail_call)

    @ExplodeLoop
    private fun executeRands(frame: VirtualFrame): Array<Any> = rands.map { it.execute(frame) }.toTypedArray()

    // TODO: make sure this is in whnf (assert?)
    override fun execute(frame: VirtualFrame): Any = callWhnf.execute(frame, rator.execute(frame), executeRands(frame))

    override fun hasTag(tag: Class<out Tag>?) = tag == StandardTags.CallTag::class.java || super.hasTag(tag)
  }

  class ConApp(
    @field:Child var x: Rhs.ArgCon
  ): Code(null) {
    override fun execute(frame: VirtualFrame): Any = x.execute(frame)
  }

  class Lit(val x: Any): Code(null) {
    override fun execute(frame: VirtualFrame): Any = x
  }

  // TODO: set loc from defLoc
  class Let(
    val slot: FrameSlot,
    @field:Child var value: Rhs,
    @field:Child var body: Code
  ): Code(null) {
    override fun execute(frame: VirtualFrame): Any? {
      frame.setObject(slot, value.execute(frame))
      return body.execute(frame)
    }
  }

  class LetRec(
    @CompilerDirectives.CompilationFinal(dimensions = 1) val slots: Array<FrameSlot>,
    @field:Children val values: Array<Rhs>,
    @field:Child var body: Code
  ) : Code(null) {
    @ExplodeLoop
    override fun execute(frame: VirtualFrame): Any? {
      val cs = slots.map {
        val t = Thunk(null, null)
        frame.setObject(it, t)
        t
      }
      values.forEachIndexed { ix, rhs ->
        val x = rhs.execute(frame)
        if (x is Thunk) {
          if (x.clos === null) {
            cs[ix].value_ = x.value_
            frame.setObject(slots[ix], x.value_)
          } else {
            // this should be safe/not result in duplicate computation:
            // x was just allocated by a Lam = can't be ref'd anywhere else
            // since Rhs is either a Lam or a data constructor
            cs[ix].clos = x.clos
          }
        } else {
          frame.setObject(slots[ix], x)
          cs[ix].value_ = x
        }
      }
      return body.execute(frame)
    }
  }

  class Case(
    @field:Child var thing: Code,
    val evaluatedSlot: FrameSlot,
    @field:Child var alts: CaseAlts,
    @field:Child var default: Code?
  ) : Code(null) {
    override fun execute(frame: VirtualFrame): Any? {
      val x = thing.execute(frame)
      frame.setObject(evaluatedSlot, x)
      val y = alts.execute(frame, x)
      // TODO: this null check doesn't always get optimized away by graal, mb remove it?
      if (y != null) return y
      return (default ?: panic("bad case")).execute(frame)
    }
  }
}

@TypeSystemReference(DataTypes::class)
abstract class CaseAlts : Node() {
  abstract fun execute(frame: VirtualFrame, x: Any?): Any?

  class PrimAlts(
    @CompilerDirectives.CompilationFinal(dimensions = 1) val alts: Array<Any>,
    @field:Children val bodies: Array<Code>
  ) : CaseAlts() {
    @CompilerDirectives.CompilationFinal(dimensions = 1) val profiles: Array<BranchProfile> = Array(alts.size) { BranchProfile.create() }

    @ExplodeLoop
    override fun execute(frame: VirtualFrame, x: Any?): Any? {
      // TODO: assert x is actually a primitive
      alts.forEachIndexed { ix, y ->
        if (x == y) {
          profiles[ix].enter()
          return bodies[ix].execute(frame)
        }
      }
      return null
    }
  }

  class UnboxedTuple(
    val ty: Stg.Type,
    val arity: Int,
    @CompilerDirectives.CompilationFinal(dimensions = 1) val fieldSlots: Array<FrameSlot>,
    @field:Child var body: Code
  ): CaseAlts() {
    override fun execute(frame: VirtualFrame, x: Any?): Any? {
      if (arity > 0) {
        if (x !is trufflestg.data.UnboxedTuple) { panic{"CaseUnboxedTuple of $x"} }
        if (x.x.size != arity) { panic("CaseUnboxedTuple: wrong arity") }
        fieldSlots.forEachIndexed  { ix, sl -> frame.setObject(sl, x.x[ix]) }
      }
      return body.execute(frame)
    }
  }

  class AlgAlts(
    val ty: TyCon,
    @CompilerDirectives.CompilationFinal(dimensions = 1) val cons: Array<DataConInfo>,
    @CompilerDirectives.CompilationFinal(dimensions = 2) val slots: Array<Array<FrameSlot>>,
    @field:Children val bodies: Array<Code>
  ): CaseAlts() {
    @CompilerDirectives.CompilationFinal(dimensions = 1) val profiles: Array<BranchProfile> = Array(cons.size) { BranchProfile.create() }

    @ExplodeLoop
    override fun execute(frame: VirtualFrame, x: Any?): Any? {
      if (ty.cons.size == 1 && ty.cons.contentEquals(cons)) {
        val x2 = CompilerDirectives.castExact(x, cons[0].klass)
        slots[0].forEachIndexed { sx, s -> frame.setObject(s, x2.getValue(sx)) }
        return bodies[0].execute(frame)
      } else {
        cons.forEachIndexed { ix, c ->
          if (c.size == 0) {
            // TODO: use is ZeroArgDataCon and tag instead? might let it be compiled as a switch instead of ifs
            if (c.zeroArgCon!! === x) {
              profiles[ix].enter()
              return bodies[ix].execute(frame)
            }
          } else {
            // TODO: use CompilerDirectives.isExact once its released
            if (c.klass!!.isInstance(x)) {
              val x2 = CompilerDirectives.castExact(x, c.klass)
              profiles[ix].enter()
              val sls = slots[ix]
              sls.forEachIndexed { sx, s -> frame.setObject(s, x2.getValue(sx)) }
              return bodies[ix].execute(frame)
            }
          }
        }
      }
      return null
    }
  }

  // used to whnf unknown types
  class PolyAlt: CaseAlts() {
    // just return null, there has to be a default case
    override fun execute(frame: VirtualFrame, x: Any?): Any? = null
  }
}

@NodeInfo(language = "arg", description = "argument of a function or data constructor")
@TypeSystemReference(DataTypes::class)
abstract class Arg : Node() {
  abstract fun execute(frame: VirtualFrame): Any

  abstract class Var: Arg()


  class Global(
    val module: Module,
    // TODO
//    val name: String,
    val id: Stg.BinderId
  ) : Var() {
    @field:CompilerDirectives.CompilationFinal var isResolved: Boolean = false
    @field:CompilerDirectives.CompilationFinal var value: Any? = null

    override fun execute(frame: VirtualFrame): Any {
      if (!isResolved) {
        CompilerDirectives.transferToInterpreterAndInvalidate()
        value = module.resolveId(id)
        isResolved = true
      }
      return value!!
    }
  }

  class Local(
    val id: Stg.BinderId,
    val slot: FrameSlot
  ): Var() {
    // TODO: executeStgInt etc, but only once i have nodes that would use them
    // executeLong only helps if i have nodes that are easier to PE if called with them, i think
    // TODO: if i know the expected type, i can setWhatever after getObject, which should help PE & might help graal(?)
    // also could do the same in ClosureRootNode
    // see https://github.com/oracle/graal/issues/627 & sl for what other langs do
    override fun execute(frame: VirtualFrame): Any {
      return frame.getObject(slot)
    }
  }

  class Lit(val x: Any): Arg() {
    override fun execute(frame: VirtualFrame): Any = x
  }
}


@NodeInfo(language = "rhs", description = "rhs of a let")
@TypeSystemReference(DataTypes::class)
// TODO: InstrumentableNode
abstract class Rhs : Node() {
  abstract fun execute(frame: VirtualFrame): Any

  @Suppress("NOTHING_TO_INLINE")
  @TypeSystemReference(DataTypes::class)
  @NodeInfo(shortName = "Lambda")
  // a StgRhsClosure
  class RhsClosure(
    @CompilerDirectives.CompilationFinal(dimensions = 1) val captures: Array<FrameSlot>,
    val arity: Int,
    val updFlag: Stg.UpdateFlag,
    @field:CompilerDirectives.CompilationFinal
    internal var callTarget: RootCallTarget
  ) : Rhs() {
    @Child var builder: BuildFrame = BuildFrameNodeGen.create()

    // do we need to capture an environment?
    private inline fun isSuperCombinator() = captures.isNotEmpty()

    // TODO: statically allocate the Closure when possible (when no env)
    // split between capturing Lam and not?
    // might help escape analysis w/ App
    override fun execute(frame: VirtualFrame): Any = when {
      updFlag == Stg.UpdateFlag.Updatable && arity == 0 -> Thunk(Closure(captureEnv(frame), arity, callTarget), null)
      updFlag == Stg.UpdateFlag.ReEntrant -> Closure(captureEnv(frame), arity, callTarget)
      // ghc says SingleEntry = don't need to blackhole or update http://hackage.haskell.org/package/ghc-8.10.2/docs/src/StgSyn.html#UpdateFlag
      // TODO: is this right?
      updFlag == Stg.UpdateFlag.SingleEntry && arity == 0 -> Closure(captureEnv(frame), arity, callTarget)
      else -> panic("todo")
    }

    @ExplodeLoop
    private fun captureEnv(frame: VirtualFrame): Array<Any> {
      if (!isSuperCombinator()) return emptyEnv
      // TODO: skip the frame when captures.size == 1
      val cs = map(captures) { frame.getValue(it) }
      return arrayOf(builder.execute(cs))
    }
  }

//  // a StgRhsClosure that is just doing partial application = we can avoid the intermediate call
//  class RhsPap(
//    @Child var x: Arg.Var,
//    @Children val args: Array<Arg>,
//    val updFlag: Stg.UpdateFlag
//  ) : Rhs() {
//    override fun execute(frame: VirtualFrame): Any = todo
//  }

  class ArgCon(
    val con: DataConInfo,
    @field:Children val args: Array<Arg>
  ) : Rhs() {
    @ExplodeLoop
    override fun execute(frame: VirtualFrame): Any {
      val xs = map(args) { it.execute(frame) }
      if (con.name.unitId == "ghc-prim" && con.name.module == "GHC.Prim" &&
          (con.name.name.startsWith("(#") || con.name.name == "Unit#")) {
        return UnboxedTuple(xs)
      }
      return con.build(xs)
    }
  }
}
