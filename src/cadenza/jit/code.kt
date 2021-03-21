package cadenza.jit

import cadenza.*
import cadenza.data.*
import cadenza.frame.BuildFrame
import cadenza.frame.BuildFrameNodeGen
import cadenza.stg_types.Stg
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
import cadenza.array_utils.map

// utility
@Suppress("NOTHING_TO_INLINE")
private inline fun isSuperCombinator(callTarget: RootCallTarget) =
  callTarget.rootNode.let { it is ClosureRootNode && it.isSuperCombinator() }

@ReportPolymorphism
@GenerateWrapper
@NodeInfo(language = "core", description = "core nodes")
@TypeSystemReference(DataTypes::class)
abstract class Code(val loc: Loc?) : Node(), InstrumentableNode {
  constructor(that: Code) : this(that.loc)

  // should never return null, the Any? is just to get kotlin to not insert null checks...
  abstract fun execute(frame: VirtualFrame): Any?

  override fun getSourceSection(): SourceSection? = loc?.let { rootNode?.sourceSection?.source?.section(it) }
  override fun isInstrumentable() = loc !== null

  override fun hasTag(tag: Class<out Tag>?) = tag == StandardTags.ExpressionTag::class.java
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
    private fun executeRands(frame: VirtualFrame): Array<Any?> = rands.map { it.execute(frame) }.toTypedArray()

    // TODO: make sure this is in whnf (assert?)
    override fun execute(frame: VirtualFrame): Any = callWhnf.execute(frame, rator.execute(frame), executeRands(frame))

    override fun hasTag(tag: Class<out Tag>?) = tag == StandardTags.CallTag::class.java || super.hasTag(tag)
  }

//  class LetRec(
//    val slot: FrameSlot,
//    @field:Child var value: Code,
//    @field:Child var body: Code,
//    loc: Loc?
//  ): Code(loc) {
//    @CompilerDirectives.CompilationFinal var readTarget: RootCallTarget? = null
//
//    override fun execute(frame: VirtualFrame): Any? {
//      if (readTarget === null) {
//        CompilerDirectives.transferToInterpreterAndInvalidate()
//        val language = lookupLanguageReference(Language::class.java).get()
//        readTarget = Truffle.getRuntime().createCallTarget(ReadIndirectionRootNode(language))
//      }
//
//      val indir = Indirection()
//      val clos = Closure(null, arrayOf(indir), 0, Type.Arr(Type.Obj,type), readTarget!!)
//      // need to set it here in case a lambda in value captures it
//      frame.setObject(slot, clos)
//      val x = value.executeAny(frame)
//      // ... but if not, we can avoid the indirection
//      // and need to set it here anyways in case value shadows us with a let
////      frame.setObject(slot, x)
//      frame.setObject(slot, clos)
//      indir.value = x
//      indir.set = true
//      return body.execute(frame)
//    }
//  }

  class ConApp(
    @field:Child var x: Rhs.ArgCon
  ): Code(null) {
    override fun execute(frame: VirtualFrame): Any = x.execute(frame)
  }

  class Lit(val x: Any): Code(null) {
    override fun execute(frame: VirtualFrame): Any = x
  }

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
    override fun execute(frame: VirtualFrame): Any? {
      val cs = slots.map {
        val t = Thunk(null, null)
        frame.setObject(it, t)
        t
      }
      values.forEachIndexed { ix, rhs ->
        val x = rhs.execute(frame)
        if (x is Thunk) {
          cs[ix].clos = x.clos
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
      if (y != null) return y
      return default?.execute(frame) ?: panic("bad case")
    }
  }
}

@TypeSystemReference(DataTypes::class)
@ReportPolymorphism
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
        if (x !is cadenza.data.UnboxedTuple) { panic{"CaseUnboxedTuple of $x"} }
        if (x.x.size != arity) { panic("CaseUnboxedTuple: wrong arity") }
        fieldSlots.forEachIndexed  { ix, sl -> frame.setObject(sl, x.x[ix]) }
      }
      return body.execute(frame)
    }
  }

  class AlgAlts(
    val ty: TyCon,
    @CompilerDirectives.CompilationFinal(dimensions = 1) val cons: Array<Pair<DataCon,Array<FrameSlot>>>,
    @field:Children val bodies: Array<Code>
  ): CaseAlts() {
    @CompilerDirectives.CompilationFinal(dimensions = 1) val profiles: Array<BranchProfile> = Array(cons.size) { BranchProfile.create() }

    @ExplodeLoop
    override fun execute(frame: VirtualFrame, x: Any?): Any? {
//      val y = whnf(x) as StgData
      if (x !is StgData) { panic("AlgAlts") }
      cons.forEachIndexed { ix, (c, sls) ->
        if (c === x.con) {
          profiles[ix].enter()
          if (x.args.size != sls.size) { panic("AlgAlts: con size mismatch") }
          sls.forEachIndexed { sx, s -> frame.setObject(s, x.args[sx]) }
          return bodies[ix].execute(frame)
        }
      }
      return null
    }
  }

  // used to evaluate unknown types
  class PolyAlt: CaseAlts() {
    override fun execute(frame: VirtualFrame, x: Any?): Any? = null
  }
}

@NodeInfo(language = "arg", description = "argument of a function or data constructor")
@TypeSystemReference(DataTypes::class)
@ReportPolymorphism
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
    override fun execute(frame: VirtualFrame): Any {
      return frame.getObject(slot) ?: panic("null Local")
    }
  }

  class Lit(val x: Any): Arg() {
    override fun execute(frame: VirtualFrame): Any = x
  }
}


@NodeInfo(language = "rhs", description = "rhs of a let")
@TypeSystemReference(DataTypes::class)
@ReportPolymorphism
// TODO: InstrumentableNode
abstract class Rhs : Node() {
  abstract fun execute(frame: VirtualFrame): Any

  @Suppress("NOTHING_TO_INLINE")
  @TypeSystemReference(DataTypes::class)
  @NodeInfo(shortName = "Lambda")
  // when arity == 0 need to allocate a thunk (if Updatable)
  // TODO: or a nullary Closure if ReEntrant ?
  // a StgRhsClosure
  class RhsClosure(
    private val closureFd: FrameDescriptor?,
    @CompilerDirectives.CompilationFinal(dimensions = 1) val captures: Array<Pair<FrameSlot,FrameSlot>>,
    val arity: Int,
    val updFlag: Stg.UpdateFlag,
    @field:CompilerDirectives.CompilationFinal
    internal var callTarget: RootCallTarget
  ) : Rhs() {
    @Child var builder: BuildFrame = BuildFrameNodeGen.create()

    // do we need to capture an environment?
    private inline fun isSuperCombinator() = !captures.isEmpty()

    // TODO: statically allocate the Closure when possible (when no env)
    // split between capturing Lam and not?
    // might help escape analysis w/ App
    override fun execute(frame: VirtualFrame): Any = when {
      updFlag == Stg.UpdateFlag.Updatable && arity == 0 -> Thunk(Closure(captureEnv(frame), arrayOf(), arity, callTarget), null)
      updFlag == Stg.UpdateFlag.ReEntrant && arity > 0 -> Closure(captureEnv(frame), arrayOf(), arity, callTarget)
      // TODO: ghc says SingleEntry = don't need to blackhole or update http://hackage.haskell.org/package/ghc-8.10.2/docs/src/StgSyn.html#UpdateFlag
      // TODO: is this right?
      updFlag == Stg.UpdateFlag.SingleEntry && arity == 0 -> Closure(captureEnv(frame), arrayOf(), arity, callTarget)
      else -> panic("todo")
    }   //Closure(captureEnv(frame), arrayOf(), arity, callTarget)
//    override fun executeClosure(frame: VirtualFrame): Closure = Closure(captureEnv(frame), arrayOf(), arity, callTarget)

    @ExplodeLoop
    private fun captureEnv(frame: VirtualFrame): MaterializedFrame? {
      if (!isSuperCombinator()) return null
      val newFrame = Truffle.getRuntime().createVirtualFrame(noArguments, closureFd)
      captures.forEach { newFrame.setObject(it.first, frame.getObject(it.second)) }
      return newFrame.materialize()
    }
  }

  class ArgCon(
    val con: DataCon,
    @field:Children val args: Array<Arg>
  ) : Rhs() {
    @ExplodeLoop
    override fun execute(frame: VirtualFrame): Any {
      val xs = map(args) { it.execute(frame) }
      if (con.name.unitId == "ghc-prim" && con.name.module == "GHC.Prim" &&
          (con.name.name.startsWith("(#") || con.name.name == "Unit#")) {
        return UnboxedTuple(xs)
      }
      return StgData(con, xs)
    }
  }
}
