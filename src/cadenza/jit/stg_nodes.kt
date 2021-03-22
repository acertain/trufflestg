package cadenza.jit

import cadenza.Language
import cadenza.data.*
import cadenza.panic
import cadenza.stg_types.*
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.FrameSlot
import com.oracle.truffle.api.source.Source
import com.oracle.truffle.api.source.SourceSection
import cadenza.array_utils.map

data class CompileInfo(
  val module: Module,
  // the TopLevel we're compiling
  val topLevel: TopLevel
)

fun Stg.BinderId.compile(ci: CompileInfo, fd: FrameDescriptor): Arg.Var {
  val y = fd.findFrameSlot(this)
  return when {
    y != null -> Arg.Local(this, y)
    ci.module.hasId(this) -> Arg.Global(ci.module, this)
    else -> panic("bad stg: unknown id")
  }
}

@OptIn(ExperimentalUnsignedTypes::class)
fun Stg.Arg.compile(ci: CompileInfo, fd: FrameDescriptor): Arg = when (this) {
  is Stg.Arg.StgLitArg -> Arg.Lit(x.compile())
  is Stg.Arg.StgVarArg -> x.compile(ci, fd)
}

fun Stg.Lit.compile(): Any = when (this) {
  is Stg.Lit.LitChar -> StgChar(x.codePointAt(0))
  is Stg.Lit.LitDouble -> TODO()
  is Stg.Lit.LitFloat -> TODO()
  is Stg.Lit.LitLabel -> TODO()
  is Stg.Lit.LitNullAddr -> NullAddr
  is Stg.Lit.LitNumber -> when (x) {
    Stg.LitNumType.LitNumInt -> StgInt(y.toLong())
    Stg.LitNumType.LitNumInt -> StgInt(y.toLong())
    Stg.LitNumType.LitNumInt64 -> TODO()
    Stg.LitNumType.LitNumWord -> StgWord(y.toLong().toULong())
    Stg.LitNumType.LitNumWord64 -> TODO()
  }
  is Stg.Lit.LitString -> StgAddr(x, 0)
}

fun Stg.Expr.compile(ci: CompileInfo, fd: FrameDescriptor, tc: Boolean): Code = when(this) {
  is Stg.Expr.StgApp -> Code.App(x.compile(ci, fd), args.map { it.compile(ci, fd) }.toTypedArray(), tc)
  is Stg.Expr.StgCase -> {
    val default = alts.find { it.con is Stg.AltCon.AltDefault }
    val alts = this.alts.filter { it.con !is Stg.AltCon.AltDefault }
    default?.binders?.isEmpty()?.let { assert(it) }
    Code.Case(
      x.compile(ci, fd, false),
      fd.addFrameSlot(bnd.binderId),
      when (altTy) {
        is Stg.AltType.AlgAlt -> {
          CaseAlts.AlgAlts(
            ci.module.tyCons[altTy.x]!!,
            alts.map { ci.module.dataCons[(it.con as Stg.AltCon.AltDataCon).x]!! }.toTypedArray(),
            alts.map { map(it.binders) { x -> fd.addFrameSlot(x.binderId) } }.toTypedArray(),
            alts.map { it.rhs.compile(ci, fd, tc) }.toTypedArray()
          )
        }
        is Stg.AltType.MultiValAlt -> when {
          bnd.type is Stg.Type.UnboxedTuple && alts.size == 1 -> CaseAlts.UnboxedTuple(
            bnd.type,
            bnd.type.rep.size,
            alts[0].binders.map { fd.addFrameSlot(it.binderId) }.toTypedArray(),
            alts[0].rhs.compile(ci, fd, tc)
          )
          else -> TODO("$this")
        }
        is Stg.AltType.PolyAlt -> when {
          alts.isEmpty() -> CaseAlts.PolyAlt()
          else -> TODO("$this")
        }
        is Stg.AltType.PrimAlt -> when {
          alts.all {  it.con is Stg.AltCon.AltLit && it.binders.isEmpty() } -> {
            val (cs, bs) = alts.map { (it.con as Stg.AltCon.AltLit).x.compile() to it.rhs.compile(ci, fd, tc) }.unzip()
            CaseAlts.PrimAlts(cs.toTypedArray(), bs.toTypedArray())
          }
          else -> TODO("$this")
        }
      },
      default?.rhs?.compile(ci, fd, tc)
    )
  }
  is Stg.Expr.StgConApp -> Code.ConApp(Rhs.ArgCon(ci.module.dataCons[x]!!, map(args) { it.compile(ci, fd) }))
  is Stg.Expr.StgLet -> when (x) {
    is Stg.Binding.StgNonRec -> Code.Let(fd.addFrameSlot(x.x.binderId), x.y.compile(x.x, ci, fd), body.compile(ci, fd, tc))
    is Stg.Binding.StgRec -> {
      val xs = x.x.map { fd.addFrameSlot(it.first.binderId) }.toTypedArray()
      val ys = x.x.map { it.second.compile(it.first, ci, fd) }.toTypedArray()
      Code.LetRec(xs, ys, body.compile(ci, fd, tc))
    }
  }
  is Stg.Expr.StgLetNoEscape -> when (x) {
    is Stg.Binding.StgNonRec -> Code.Let(fd.addFrameSlot(x.x.binderId), x.y.compile(x.x, ci, fd), body.compile(ci, fd, tc))
    is Stg.Binding.StgRec -> {
      val xs = x.x.map { fd.addFrameSlot(it.first.binderId) }.toTypedArray()
      val ys = x.x.map { it.second.compile(it.first, ci, fd) }.toTypedArray()
      Code.LetRec(xs, ys, body.compile(ci, fd, tc))
    }
  }
  is Stg.Expr.StgLit -> Code.Lit(x.compile())
  is Stg.Expr.StgOpApp -> when (op) {
    is Stg.StgOp.StgFCallOp -> StgFCall(op.x, map(args) { it.compile(ci, fd) })
    is Stg.StgOp.StgPrimCallOp -> StgPrimCall(op.x, map(args) { it.compile(ci, fd) })
    is Stg.StgOp.StgPrimOp -> StgPrim(ci.module.tyCons[tn.orElse(null)], op.x, map(args) { it.compile(ci, fd) })
  }
  is Stg.Expr.StgTick -> TODO("$this")
}


fun Stg.Rhs.compile(bi: Stg.SBinder, ci: CompileInfo, fd: FrameDescriptor): Rhs = when(this) {
  is Stg.Rhs.StgRhsClosure -> this.compileC(bi, ci, fd)
  is Stg.Rhs.StgRhsCon -> Rhs.ArgCon(ci.module.dataCons[con]!!, map(args) { it.compile(ci, fd) })
}

fun Stg.SrcSpan.build(): SourceSection? = when (this) {
  is Stg.SrcSpan.ARealSrcSpan -> null
//    Source.newBuilder("cadenza", "", sp.file)
//          .content(Source.CONTENT_NONE).build()
//          .createSection(sp.sline, sp.scol, sp.eline, sp.ecol)
  is Stg.SrcSpan.UnhelpfulSpan -> null
}

// TODO: convert Let rhs=Ap into a Pap when possible (avoid unnecessary Closure indirection)
fun Stg.Rhs.StgRhsClosure.compileC(bi: Stg.SBinder, ci: CompileInfo, fd: FrameDescriptor): Rhs {
  val bodyFd = FrameDescriptor()

  val captures = arrayListOf<FrameSlot>()
  val envPreamble = arrayListOf<Pair<FrameSlot,Int>>()

  fvs.forEachIndexed { ix, id ->
    val slot = bodyFd.addFrameSlot(id)
    val parentSlot = fd.findFrameSlot(id)

    captures += parentSlot
    envPreamble += Pair(slot, ix)
  }
  val argPreamble = bnds.mapIndexed { ix, b -> Pair(bodyFd.addFrameSlot(b.binderId), ix) }

  val bodyCode = body.compile(ci, bodyFd, true)

  return Rhs.RhsClosure(
    captures.toTypedArray(),
    bnds.size,
    upd,
    Truffle.getRuntime().createCallTarget(
      ClosureRootNode(
        ci.module.language,
        bodyFd,
        bnds,
        envPreamble.toTypedArray(),
        argPreamble.toTypedArray(),
        ClosureBody(bodyCode),
        ci.module,
        ci.topLevel,
        bi.defLoc.build(),
        upd
      )
    )
  )
}

fun Array<Stg.Arg>.fixupFvs(m: Module): Set<Stg.BinderId> = this.flatMap { it.fixupFvs(m) }.toSet()

fun Stg.Binding.binderIds(): Set<Stg.BinderId> = when (this) {
  is Stg.Binding.StgNonRec -> setOf(x.binderId)
  is Stg.Binding.StgRec -> x.map { it.first.binderId }.toSet()
}

fun Stg.Binding.fixupFvs(m: Module): Set<Stg.BinderId> = when (this) {
  is Stg.Binding.StgNonRec -> y.fixupFvs(m)
  is Stg.Binding.StgRec -> x.flatMap { it.second.fixupFvs(m) }.toSet() - x.map { it.first.binderId }.toSet()
}

// returns local fvs + a random subset of global fvs
fun Stg.Expr.fixupFvs(m: Module): Set<Stg.BinderId> = when (this) {
  is Stg.Expr.StgApp -> args.fixupFvs(m) + x
  is Stg.Expr.StgCase ->
    (x.fixupFvs(m) + alts.flatMap { it.rhs.fixupFvs(m) - it.binders.map { x -> x.binderId } }.toSet()) - bnd.binderId
  is Stg.Expr.StgConApp -> args.fixupFvs(m)
  is Stg.Expr.StgLet -> (body.fixupFvs(m) + x.fixupFvs(m)) - x.binderIds()
  is Stg.Expr.StgLetNoEscape -> (body.fixupFvs(m) + x.fixupFvs(m)) - x.binderIds()
  is Stg.Expr.StgLit -> emptySet()
  is Stg.Expr.StgOpApp -> args.fixupFvs(m)
  is Stg.Expr.StgTick -> body.fixupFvs(m)
}

fun Stg.Arg.fixupFvs(m: Module): Set<Stg.BinderId> = when (this) {
  is Stg.Arg.StgLitArg -> emptySet()
  is Stg.Arg.StgVarArg -> setOf(x)
}

fun Stg.Rhs.fixupFvs(m: Module): Set<Stg.BinderId> = when (this) {
  is Stg.Rhs.StgRhsClosure -> {
    val r = (body.fixupFvs(m) - bnds.map { it.binderId }).filter { !m.hasId(it) }.toSet()
    fvs = r.toTypedArray()
    r
  }
  is Stg.Rhs.StgRhsCon -> args.fixupFvs(m)
}


sealed class TopLevel(
  val binder: Stg.SBinder,
  val module: Module,
) {
  val name: String = binder.name
  val fullName: String = module.fullName + "." + name
  abstract fun getValue(): Any
  override fun toString(): String = fullName

  class Function(
    module: Module,
    binder: Stg.SBinder,
    body: Stg.Rhs.StgRhsClosure
  ) : TopLevel(binder, module) {
    val closure: Any by lazy {
      body.fixupFvs(module)
      val fd = FrameDescriptor()
      val fr = Truffle.getRuntime().createVirtualFrame(arrayOf(), fd)
      body.compile(binder, CompileInfo(module, this), fd).execute(fr)
    }
    override fun getValue(): Any = closure
  }
  class StringLit(
    module: Module,
    binder: Stg.SBinder,
    val string: ByteArray
  ) : TopLevel(binder, module) {
    val x = StgAddr(string, 0)
    override fun getValue(): Any = x
  }
  class DataCon(
    module: Module,
    binder: Stg.SBinder,
    con: Stg.Rhs.StgRhsCon
  ) : TopLevel(binder, module) {
    val con: Any by lazy {
      val fd = FrameDescriptor()
      val fr = Truffle.getRuntime().createVirtualFrame(arrayOf(), fd)
      con.compile(binder, CompileInfo(module, this), fd).execute(fr)
    }
    override fun getValue(): Any = con
  }
}


class CborModuleDir(
  val language: Language,
  val path: String
) {
  val loadedModules: MutableMap<String,Module> = mutableMapOf()

  operator fun get(name: String): Module? {
    if (name in loadedModules) return loadedModules[name]
    val mpath = path + name
    print("loading $mpath... ")
    val c = readModule(mpath)
    println("done")
    val m = Module(language, this, c)
    loadedModules[name] = m
    return m
  }
}

// contents of magic GHC.Prim module
val prims: Map<String,Any> = mapOf(
  "void#" to VoidInh,
  "realWorld#" to VoidInh,
  "coercionToken#" to VoidInh,
//  "void#" to
)

class Module(
  val language: Language,
  val moduleDir: CborModuleDir,
  src: Stg.Module
) {
  val unitId: String = src.unitId.x
  val name: String = src.name.x
  val fullName = unitId + ":" + name

  val external_ids: Map<Stg.BinderId, Pair<Pair<Stg.UnitId, Stg.ModuleName>, Stg.SBinder>> =
    src.externalTopIds
      .flatMap { it.second.flatMap { x -> x.second.map { y -> y.binderId to ((it.first to x.first) to y) } } }
      .associate { x -> x }

  val top_bindings: Map<Stg.BinderId, TopLevel> = src.topBindings.flatMap {
    fun rhs(b: Stg.SBinder, r: Stg.Rhs): TopLevel = when (r) {
      is Stg.Rhs.StgRhsClosure -> TopLevel.Function(this, b, r)
      is Stg.Rhs.StgRhsCon -> TopLevel.DataCon(this, b, r)
    }

    when(it) {
      is Stg.TopBinding.StgTopLifted -> when(it.x) {
        is Stg.Binding.StgNonRec -> listOf(rhs(it.x.x, it.x.y))
        is Stg.Binding.StgRec -> it.x.x.map { x -> rhs(x.first, x.second) }
      }
      is Stg.TopBinding.StgTopStringLit -> listOf(TopLevel.StringLit(this, it.x, it.y))
    }
  }.associateBy { it.binder.binderId }

//  val (tyCons, dataCons) = TODO()

  val tyCons: Map<Stg.TyConId, TyCon>
  val dataCons: Map<Stg.DataConId, DataCon>
  init {
    val tyCons1: Map<Stg.TyConId, Pair<FullName, Stg.STyCon>> =
      src.tyCons
        .flatMap { it.second.flatMap {
          x -> x.second.map {
          y -> y.id to (FullName(it.first.x, x.first.x, y.name) to y) } } }
        .associate { x -> x }

    tyCons = tyCons1.mapValues { y -> TyCon.parse(y.value.first, y.value.second) }

    dataCons = tyCons1
      .flatMap { it.value.second.dataCons.mapIndexed { ix, x -> x.id to tyCons[it.key]!!.cons[ix] } }.associate { x -> x }
  }


  val names: Map<String, Stg.BinderId> = top_bindings.asIterable().associate { it.value.binder.name to it.key }

  fun resolveId(id: Stg.BinderId): Any = when {
    id in external_ids -> {
      val x = external_ids[id]!!
      if (x.first.second.x == "GHC.Prim") {
        val n = x.second.name
        prims[x.second.name] ?: TODO("GHC.Prim.$n not implemented: $x")
      } else {
        moduleDir[x.first.second.x]!![x.second.name]!!
      }
    }
    id in top_bindings -> top_bindings[id]!!.getValue()
    else -> panic("Bad stg: attempted to resolve non-existent BinderId")
  }

  fun hasId(id: Stg.BinderId): Boolean = id in external_ids || id in top_bindings

  operator fun get(name: String): Any? = top_bindings[names[name]]?.getValue()

}


