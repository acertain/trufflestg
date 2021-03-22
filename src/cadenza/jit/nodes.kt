package cadenza.jit

import cadenza.Language
import cadenza.Loc
import cadenza.data.DataTypes
import cadenza.panic
import cadenza.section
import cadenza.stg_types.Stg
import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.dsl.TypeSystemReference
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.FrameSlot
import com.oracle.truffle.api.frame.MaterializedFrame
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.instrumentation.*
import com.oracle.truffle.api.nodes.*
import com.oracle.truffle.api.profiles.BranchProfile
import com.oracle.truffle.api.source.Source
import com.oracle.truffle.api.source.SourceSection

internal val noArguments = arrayOf<Any>()

// code and statements, and other things with source locations that aren't root or root-like
abstract class LocatedNode(val loc: Loc? = null) : Node(), InstrumentableNode {
  override fun getSourceSection(): SourceSection? = loc?.let { rootNode?.sourceSection?.source?.section(it) }
  override fun isInstrumentable() = loc !== null
}

abstract class CadenzaRootNode(
  language: Language,
  fd: FrameDescriptor
) : RootNode(language, fd) {
  open val mask: Long = hashCode().run {
    1L shl and(0x3f) or
      (1L shl (shr(6) and 0x3f)) or
      (1L shl (shr(12) and 0x3f)) or
      (1L shl (shr(18) and 0x3f)) or
      (1L shl (shr(24) and 0x3f))
  }
}

@NodeInfo(language = "core", description = "A root of a core tree.")
@TypeSystemReference(DataTypes::class)
open class ProgramRootNode constructor(
  language: Language,
  @field:Child private var body: Code,
  fd: FrameDescriptor,
  val source: Source
) : CadenzaRootNode(language, fd) {
  val target = Truffle.getRuntime().createCallTarget(this)

  @Child var tailCallLoop = TailCallLoop()

  override fun isCloningAllowed() = true
  override fun execute(frame: VirtualFrame): Any? {
    return try {
      body.execute(frame)
    } catch (tailCall: TailCallException) {
      tailCallLoop.execute(tailCall)
    }
  }

  override fun getSourceSection(): SourceSection = source.createSection(0, source.length)
  override fun getName() = "program root"
}



@GenerateWrapper
open class ClosureBody constructor(
  @field:Child protected var content: Code
) : Node(), InstrumentableNode {
  constructor(that: ClosureBody) : this(that.content)

  open fun execute(frame: VirtualFrame): Any? = content.execute(frame)
  override fun isInstrumentable() = true
  override fun createWrapper(probe: ProbeNode): InstrumentableNode.WrapperNode = ClosureBodyWrapper(this, this, probe)
  override fun hasTag(tag: Class<out Tag>?) = tag == StandardTags.RootBodyTag::class.java
  override fun getSourceSection(): SourceSection? = parent.sourceSection
}

// TODO: instrumentable body prelude node w/ RootTag?
@TypeSystemReference(DataTypes::class)
open class ClosureRootNode(
  private val language: Language,
  frameDescriptor: FrameDescriptor = FrameDescriptor(),
  val argBinders: Array<Stg.SBinder>,
  // slot = closure.env[ix]
  @CompilerDirectives.CompilationFinal(dimensions = 1) val envPreamble: Array<Pair<FrameSlot, FrameSlot>>,
  @CompilerDirectives.CompilationFinal(dimensions = 1) val argPreamble: Array<Pair<FrameSlot, Int>>,
  @field:Child var body: ClosureBody,
  val module: Module,
  // the stg TopLevel we are in
  val parentTopLevel: TopLevel,
  val srcSection: SourceSection?,
  val updFlag: Stg.UpdateFlag,
) : CadenzaRootNode(language, frameDescriptor) {
  val arity = if (isSuperCombinator()) argPreamble.size + 1 else argPreamble.size

  val bloomFilterSlot: FrameSlot = frameDescriptor.findOrAddFrameSlot("<TCO Bloom Filter>")
  @field:Child var selfTailCallLoopNode = SelfTailCallLoop(body, this)
  private val tailCallProfile: BranchProfile = BranchProfile.create()

  @Suppress("NOTHING_TO_INLINE")
  inline fun isSuperCombinator() = envPreamble.isNotEmpty()

  @ExplodeLoop
  fun buildFrame(arguments: Array<Any>, local: VirtualFrame) {
    val offset = if (isSuperCombinator()) 2 else 1
    for ((slot, x) in argPreamble) local.setObject(slot, arguments[x+offset])
    if (isSuperCombinator()) { // supercombinator, given environment
      val env = (arguments[1] as? MaterializedFrame)!!
      // TODO: cache based on env type that does right read + write sequences?
      for ((slot, slot2) in envPreamble) local.setObject(slot, env.getObject(slot2))
    }
  }

  @ExplodeLoop
  private fun preamble(frame: VirtualFrame): VirtualFrame {
    val local = Truffle.getRuntime().createVirtualFrame(noArguments, frameDescriptor)
    val args = (frame.arguments as? Array<Any>)!!
    local.setLong(bloomFilterSlot, (args[0] as? Long)!! or mask)
    buildFrame(args, local)
    return local
  }

  override fun execute(oldFrame: VirtualFrame): Any? {
    val local = preamble(oldFrame)
    // force loop peeling: this allows constant folding if recursive calls have const arguments
    return try {
      body.execute(local)
    } catch (e: TailCallException) {
      tailCallProfile.enter()
      if (e.fn.rootNode !== this) { throw e }
      buildFrame(e.args, local)
      selfTailCallLoopNode.execute(local)
    }
  }

  override fun getSourceSection(): SourceSection? = srcSection //loc?.let { source.section(it) }
//  override fun isInstrumentable() = loc !== null
  override fun getQualifiedName() = parentTopLevel.fullName
  override fun getName() = parentTopLevel.fullName
//  override fun getName() = parentTopLevel.name

  override fun isCloningAllowed() = true
}

@CompilerDirectives.ValueType
class Indirection {
  var set: Boolean = false
  var value: Any? = null
}

// used for let rec
open class ReadIndirectionRootNode(
  val language: Language
): CadenzaRootNode(language, FrameDescriptor()) {
//  override val mask: Long = 0L

  override fun execute(frame: VirtualFrame): Any? {
    val indir = frame.arguments[1] as Indirection
    if (CompilerDirectives.isPartialEvaluationConstant(indir) && CompilerDirectives.inCompiledCode()) {

    }

    if (!indir.set) {
      CompilerDirectives.transferToInterpreter()
      throw Exception("let rec loop (demanded variable while evaluating it)")
    }
    return indir.value
  }
}


