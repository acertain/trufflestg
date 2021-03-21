@file:Suppress("ArrayInDataClass", "CanSealedSubClassBeObject", "unused")

package cadenza.stg_types

import cadenza.stg.Cbor
import cadenza.stg.decodeCbor
import cadenza.stg.deserializeCbor
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.util.*
import kotlin.reflect.typeOf

@Suppress("EXPERIMENTAL_FEATURE_WARNING")
class Stg {

  // cborg encodes chars as strings
  // TODO: special case it in deserializeCbor & use Char here
  data class Unique(val tag: String, val number: Long) {
    override fun toString(): String {
      val xs = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
      fun go(n: Long, cs: String): String = when {
        n < 62L -> xs[n.toInt()] + cs
        else -> {
          val q = n.div(62)
          val r = n.rem(62)
          go(q, xs[r.toInt()] + cs)
        }
      }
      return tag + go(number, "")
    }
  }

  data class RealSrcSpan(
    val file: String,
    val sline: Int,
    val scol: Int,
    val eline: Int,
    val ecol: Int
  )

  data class BufSpan(val start: Int, val end: Int)

  sealed class SrcSpan {
    data class ARealSrcSpan(val sp: RealSrcSpan, val bufsp: Optional<BufSpan>) : SrcSpan()
    data class UnhelpfulSpan(val name: String) : SrcSpan()
  }

  sealed class Tickish {
    class ProfNote : Tickish()
    class HpcTick : Tickish()
    class Breakpoint : Tickish()
    data class SourceNote(
      val sp: RealSrcSpan,
      val name: String
    ) : Tickish()
  }

  sealed class PrimRep {
    class VoidRep : PrimRep()
    class LiftedRep : PrimRep()
    class UnliftedRep : PrimRep()
    class Int8Rep : PrimRep()
    class Int16Rep : PrimRep()
    class Int32Rep : PrimRep()
    class Int64Rep : PrimRep()
    class IntRep : PrimRep()
    class Word8Rep : PrimRep()
    class Word16Rep : PrimRep()
    class Word32Rep : PrimRep()
    class Word64Rep : PrimRep()
    class WordRep : PrimRep()
    class AddrRep : PrimRep()
    class FloatRep : PrimRep()
    class DoubleRep : PrimRep()
    data class VecRep(val n: Int, val rep: PrimElemRep) : PrimRep()
  }

  enum class PrimElemRep {
    Int8ElemRep,
    Int16ElemRep,
    Int32ElemRep,
    Int64ElemRep,
    Word8ElemRep,
    Word16ElemRep,
    Word32ElemRep,
    Word64ElemRep,
    FloatElemRep,
    DoubleElemRep
  }

  sealed class Type {
    data class SingleValue(val rep: PrimRep) : Type()
    data class UnboxedTuple(val rep: Array<PrimRep>) : Type()
    class PolymorphicRep : Type()
  }

  inline class TyConId(val x: Unique)
  inline class DataConId(val x: Unique)

  sealed class DataConRep {
    data class AlgDataCon(val x: Array<PrimRep>) : DataConRep()
    data class UnboxedTupleCon(val x: Int) : DataConRep()
  }

  data class SDataCon(
    val name: String,
    val id: DataConId,
    val rep: DataConRep,
    val worker: SBinder,
    val defLoc: SrcSpan
  )

  data class STyCon(
    val name: String,
    val id: TyConId,
    val dataCons: Array<SDataCon>,
    val defLoc: SrcSpan
  )

//newtype CutTyCon = CutTyCon {uncutTyCon :: TyCon }

  sealed class IdDetails {
    class VanillaId : IdDetails()
    class FExportedId : IdDetails()
    class RecSelId : IdDetails()
    data class DataConWorkId(val x: DataConId) : IdDetails()
    data class DataConWrapId(val x: DataConId) : IdDetails()
    class ClassOpId : IdDetails()
    class PrimOpId : IdDetails()
    class FCallId : IdDetails()
    class TickBoxOpId : IdDetails()
    class DFunId : IdDetails()
    class CoVarId : IdDetails()
    data class JoinId(val x: Int) : IdDetails()
  }


  inline class UnitId(val x: String)
  inline class ModuleName(val x: String)
  inline class BinderId(val x: Unique) {
    override fun toString() = x.toString()
  }

  data class SBinder(
    val name: String,
    val binderId: BinderId,
    val type: Type,
    val typeSig: String,
    val scope: Scope,
    val details: IdDetails,
    val info: String,
    val defLoc: SrcSpan
  )

  enum class Scope {
    LocalScope,
    GlobalScope,
    HaskellExported,
    ForeignExported
  }

  enum class LitNumType {
    LitNumInt,
    LitNumInt64,
    LitNumWord,
    LitNumWord64
  }

  sealed class LabelSpec {
    data class FunctionLabel(val x: Optional<Int>) : LabelSpec()
    class DataLabel : LabelSpec()
  }

//  data class Rational(val x: Long, val y: Long)
  data class Rational(val x: Long, val y: Long)

  sealed class Lit {
    data class LitChar(val x: String) : Lit()
    data class LitString(val x: ByteArray) : Lit()
    class LitNullAddr : Lit()
    data class LitFloat(val x: Rational) : Lit()
    data class LitDouble(val x: Rational) : Lit()
    data class LitLabel(val x: ByteArray, val y: LabelSpec) : Lit()
    data class LitNumber(val x: LitNumType, val y: BigInteger) : Lit()
  }

  sealed class TopBinding {
    data class StgTopLifted(val x: Binding) : TopBinding()
    data class StgTopStringLit(val x: SBinder, val y: ByteArray) : TopBinding()
  }

  sealed class Binding {
    data class StgNonRec(val x: SBinder, val y: Rhs) : Binding()
    data class StgRec(val x: Array<Pair<SBinder, Rhs>>) : Binding()
  }

  sealed class Arg {
    data class StgVarArg(val x: BinderId) : Arg()
    data class StgLitArg(val x: Lit) : Arg()
  }

  sealed class Expr {
    data class StgApp(
      val x: BinderId,
      val args: Array<Arg>,
      val type: Type,
      val meta: Triple<String, String, String>
    ) : Expr()

    data class StgLit(val x: Lit) : Expr()

    data class StgConApp(
      val x: DataConId,
      val args: Array<Arg>,
      val types: Array<Type>
    ) : Expr()

    data class StgOpApp(
      val op: StgOp,
      val args: Array<Arg>,
      val type: Type,
      val tn: Optional<TyConId>
    ) : Expr()

    data class StgCase(
      val x: Expr,
      val bnd: SBinder,
      val altTy: AltType,
      val alts: Array<Alt>
    ) : Expr()

    data class StgLet(
      val x: Binding,
      val body: Expr
    ) : Expr()

    data class StgLetNoEscape(
      val x: Binding,
      val body: Expr
    ) : Expr()

    data class StgTick(
      val t: Tickish,
      val body: Expr
    ) : Expr()
  }

  sealed class AltType {
    class PolyAlt : AltType()
    data class MultiValAlt(val x: Int) : AltType()
    data class PrimAlt(val r: PrimRep) : AltType()
    data class AlgAlt(val x: TyConId) : AltType()
  }

  enum class UpdateFlag {
    ReEntrant,
    Updatable,
    SingleEntry
  }

  sealed class Rhs {
    data class StgRhsClosure(
      var fvs: Array<BinderId>,
      val upd: UpdateFlag,
      val bnds: Array<SBinder>,
      val body: Expr
    ) : Rhs()

    data class StgRhsCon(
      val con: DataConId,
      val args: Array<Arg>
    ) : Rhs()
  }

  data class Alt(
    val con: AltCon,
    val binders: Array<SBinder>,
    val rhs: Expr
  )

  sealed class AltCon {
    data class AltDataCon(val x: DataConId) : AltCon()
    data class AltLit(val x: Lit) : AltCon()
    class AltDefault : AltCon()
  }

  enum class Safety {
    PlaySafe,
    PlayInterruptible,
    PlayRisky
  }

  enum class CCallConv {
    CCallConv,
    CApiConv,
    StdCallConv,
    PrimCallConv,
    JavaScriptCallConv
  }

  sealed class SourceText {
    data class HasSourceText(val text: String) : SourceText()
    class NoSourceText : SourceText()
  }

  sealed class CCallTarget {
    data class StaticTarget(
      val text: SourceText,
      val string: String,
      val unitId: Optional<UnitId>,
      val b: Boolean
    ) : CCallTarget()

    class DynamicTarget : CCallTarget()
  }

  data class ForeignCall(
    val ctarget: CCallTarget,
    val cconv: CCallConv,
    val csafety: Safety
  )

  data class PrimCall(
    val x: String,
    val id: UnitId
  )

  sealed class StgOp {
    data class StgPrimOp(val x: String) : StgOp()
    data class StgPrimCallOp(val x: PrimCall) : StgOp()
    data class StgFCallOp(val x: ForeignCall) : StgOp()
  }

  sealed class ForeignStubs {
    class NoStubs : ForeignStubs()
    data class YesForeignStubs(
      val cheader: String,
      val csource: String
    ) : ForeignStubs()
  }

  enum class ForeignSrcLang {
    LangC,
    LangCxx,
    LangObjc,
    LangObjcxx,
    LangAsm,
    RawObject
  }

  data class Module(
    val phase: String,
    val unitId: UnitId,
    val name: ModuleName,
    val sourceFilePath: Optional<String>,
    val foreignStubs: ForeignStubs,
    val hasForeignExported: Boolean,
    val dependency: Array<Pair<UnitId, Array<ModuleName>>>,
    val externalTopIds: Array<Pair<UnitId, Array<Pair<ModuleName, Array<SBinder>>>>>,
    val tyCons: Array<Pair<UnitId, Array<Pair<ModuleName, Array<STyCon>>>>>,
    val topBindings: Array<TopBinding>,
    val foreignFiles: Array<Pair<ForeignSrcLang, String>>
  )
}

@OptIn(ExperimentalStdlibApi::class)
fun readModule(path: String): Stg.Module {
  val f = ByteBuffer.wrap(File(path).readBytes())
  val c = Cbor(decodeCbor(f))
  return deserializeCbor(typeOf<Stg.Module>(), c) as Stg.Module
}

@ExperimentalStdlibApi
@ExperimentalUnsignedTypes
fun main() {
  val path="/data/Code/ghc-whole-program-compiler-project/ghc.truffleghc/"
  val mod=path+"Main"

  val x = readModule(mod)

  print(x)


}





