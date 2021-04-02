package trufflestg.jit

import trufflestg.Language
import trufflestg.Loc
import trufflestg.data.DataTypes
import trufflestg.frame.DataFrame
import trufflestg.frame.ReadFrame
import trufflestg.frame.ReadFrameNodeGen
import trufflestg.panic
import trufflestg.section
import trufflestg.stg.Stg
import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.dsl.TypeSystemReference
import com.oracle.truffle.api.frame.*
import com.oracle.truffle.api.instrumentation.*
import com.oracle.truffle.api.nodes.*
import com.oracle.truffle.api.profiles.BranchProfile
import com.oracle.truffle.api.source.Source
import com.oracle.truffle.api.source.SourceSection
import trufflestg.data.Closure
import trufflestg.data.VoidInh
import trufflestg.stg.*

internal val noArguments = arrayOf<Any>()

//// code and statements, and other things with source locations that aren't root or root-like
//abstract class LocatedNode(val loc: Loc? = null) : Node(), InstrumentableNode {
//  override fun getSourceSection(): SourceSection? = loc?.let { rootNode?.sourceSection?.source?.section(it) }
//  override fun isInstrumentable() = loc !== null
//}

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

// wraps the Main closure so i can return it from TruffleLanguage.parse
class MainRootNode(
  val cl: Closure,
  val language: Language
): CadenzaRootNode(language, FrameDescriptor()) {
  @field:Child var callWhnf = CallWhnf(cl.arity, false)
  override fun execute(frame: VirtualFrame): Any {
    val fr = Truffle.getRuntime().createVirtualFrame(arrayOf(), FrameDescriptor())
    // TODO: should have no args?
    try {
      callWhnf.execute(fr, cl, arrayOf(VoidInh, *frame.arguments))
    } catch (e: StackOverflowError) {
      e.printStackTrace()
      throw e
    }
    return 0
  }
}

@GenerateWrapper
open class ClosureBody constructor(
  @field:Child protected var content: Code
) : Node(), InstrumentableNode {
  constructor(that: ClosureBody) : this(that.content)

  open fun execute(frame: VirtualFrame): Any? = content.execute(frame)
  override fun isInstrumentable() = true
  override fun createWrapper(probe: ProbeNode): InstrumentableNode.WrapperNode = ClosureBodyWrapper(this, this, probe)
  override fun hasTag(tag: Class<out Tag>?) = tag == StandardTags.RootBodyTag::class.java || tag == StandardTags.RootTag::class.java
  override fun getSourceSection(): SourceSection? = parent.sourceSection
}

//@GenerateWrapper
//// RootNodes can't be instrumentable, so all of the actual logic has to be here
//open class ClosureRoot(
//  frameDescriptor: FrameDescriptor = FrameDescriptor(),
//  // slot = closure.env[ix]
//  @CompilerDirectives.CompilationFinal(dimensions = 1) val envPreamble: Array<Pair<FrameSlot, Int>>,
//  @CompilerDirectives.CompilationFinal(dimensions = 1) val argPreamble: Array<Pair<FrameSlot, Int>>,
//  @field:Child var body: ClosureBody
//) : Node(), InstrumentableNode {
//  override fun execute(frame: VirtualFrame): Any? {
//
//  }
//
//  override fun isInstrumentable(): Boolean = true
//  override fun createWrapper(probe: ProbeNode): InstrumentableNode.WrapperNode = ClosureRootWrapper(this, this, probe)
//  override fun hasTag(tag: Class<out Tag>?) = tag == StandardTags.RootTag::class.java
//}

// TODO: instrumentable body prelude node w/ RootTag?
@TypeSystemReference(DataTypes::class)
open class ClosureRootNode(
  private val language: Language,
  frameDescriptor: FrameDescriptor = FrameDescriptor(),
  val argBinders: Array<Stg.SBinder>,
  // slot = closure.env[ix]
  @CompilerDirectives.CompilationFinal(dimensions = 1) val envPreamble: Array<Pair<FrameSlot, Int>>,
  @CompilerDirectives.CompilationFinal(dimensions = 1) val argPreamble: Array<Pair<FrameSlot, Int>>,
  @field:Child var body: ClosureBody,
  val module: Module,
  // the TopLevel binding we are in
  val parentTopLevel: TopLevel,
  val srcSection: SourceSection,
  val updFlag: Stg.UpdateFlag,
) : CadenzaRootNode(language, frameDescriptor) {
  val arity = if (isSuperCombinator()) argPreamble.size + 1 else argPreamble.size

  val bloomFilterSlot: FrameSlot = frameDescriptor.findOrAddFrameSlot("<TCO Bloom Filter>")
  @field:Child var selfTailCallLoopNode = SelfTailCallLoop(body, this)
  private val tailCallProfile: BranchProfile = BranchProfile.create()

  @field:Child var readFrame: ReadFrame = ReadFrameNodeGen.create(envPreamble)

  @Suppress("NOTHING_TO_INLINE")
  inline fun isSuperCombinator() = envPreamble.isNotEmpty()

  @ExplodeLoop
  fun buildFrame(arguments: Array<Any>, local: VirtualFrame) {
    val offset = if (isSuperCombinator()) 2 else 1
    for ((slot, x) in argPreamble) local.setObject(slot, arguments[x+offset])
    if (isSuperCombinator()) { // supercombinator, given environment
      readFrame.execute(local, arguments[1])
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

//  override fun getSourceSection(): SourceSection? = srcSection //loc?.let { source.section(it) }
  override fun getSourceSection(): SourceSection? = null
  override fun isInstrumentable() = true
  // FIXME: indicate somehow when we're just a closure from a fn vs are a top-level fn
  // include name or binderId from binder?
  override fun getQualifiedName() = parentTopLevel.fullName
  override fun getName() = parentTopLevel.fullName
//  override fun getName() = parentTopLevel.name

  override fun toString(): String = "$name@${Integer.toHexString(hashCode())}"

  override fun isCloningAllowed() = true
}

