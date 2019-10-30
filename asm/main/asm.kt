package org.intelligence.asm

import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*

val Block.swap: Unit get() = add(InsnNode(SWAP))
val Block.nop: Unit get() = add(InsnNode(NOP))
// math
val Block.iadd: Unit get() = add(InsnNode(IADD))
val Block.ladd: Unit get() = add(InsnNode(LADD))
val Block.fadd: Unit get() = add(InsnNode(FADD))
val Block.dadd: Unit get() = add(InsnNode(DADD))
val Block.isub: Unit get() = add(InsnNode(ISUB))
val Block.lsub: Unit get() = add(InsnNode(LSUB))
val Block.fsub: Unit get() = add(InsnNode(FSUB))
val Block.dsub: Unit get() = add(InsnNode(DSUB))
val Block.imul: Unit get() = add(InsnNode(IMUL))
val Block.lmul: Unit get() = add(InsnNode(LMUL))
val Block.fmul: Unit get() = add(InsnNode(FMUL))
val Block.dmul: Unit get() = add(InsnNode(DMUL))
val Block.idiv: Unit get() = add(InsnNode(IDIV))
val Block.ldiv: Unit get() = add(InsnNode(LDIV))
val Block.fdiv: Unit get() = add(InsnNode(FDIV))
val Block.ddiv: Unit get() = add(InsnNode(DDIV))
val Block.irem: Unit get() = add(InsnNode(IREM))
val Block.lrem: Unit get() = add(InsnNode(LREM))
val Block.frem: Unit get() = add(InsnNode(FREM))
val Block.drem: Unit get() = add(InsnNode(DREM))
val Block.ineg: Unit get() = add(InsnNode(INEG))
val Block.lneg: Unit get() = add(InsnNode(LNEG))
val Block.fneg: Unit get() = add(InsnNode(FNEG))
val Block.dneg: Unit get() = add(InsnNode(DNEG))
val Block.ishl: Unit get() = add(InsnNode(ISHL))
val Block.lshl: Unit get() = add(InsnNode(LSHL))
val Block.ishr: Unit get() = add(InsnNode(ISHR))
val Block.lshr: Unit get() = add(InsnNode(LSHR))
val Block.iushr: Unit get() = add(InsnNode(IUSHR))
val Block.lushr: Unit get() = add(InsnNode(LUSHR))
val Block.iand: Unit get() = add(InsnNode(IAND))
val Block.land: Unit get() = add(InsnNode(LAND))
val Block.ior: Unit get() = add(InsnNode(IOR))
val Block.lor: Unit get() = add(InsnNode(LOR))
val Block.ixor: Unit get() = add(InsnNode(IXOR))
val Block.lxor: Unit get() = add(InsnNode(LXOR))
fun Block.iinc(slot: Int) = add(IincInsnNode(slot, 1))
fun Block.iinc(slot: Int, amount: Int) = add(IincInsnNode(slot, amount))
val Block.i2l: Unit get() = add(InsnNode(I2L))
val Block.i2f: Unit get() = add(InsnNode(I2F))
val Block.i2d: Unit get() = add(InsnNode(I2D))
val Block.l2i: Unit get() = add(InsnNode(L2I))
val Block.l2f: Unit get() = add(InsnNode(L2F))
val Block.l2d: Unit get() = add(InsnNode(L2D))
val Block.f2i: Unit get() = add(InsnNode(F2I))
val Block.f2l: Unit get() = add(InsnNode(F2L))
val Block.f2d: Unit get() = add(InsnNode(F2D))
val Block.d2i: Unit get() = add(InsnNode(D2I))
val Block.d2l: Unit get() = add(InsnNode(D2L))
val Block.d2f: Unit get() = add(InsnNode(D2F))
val Block.i2b: Unit get() = add(InsnNode(I2B))
val Block.i2c: Unit get() = add(InsnNode(I2C))
val Block.i2s: Unit get() = add(InsnNode(I2S))
val Block.iaload: Unit get() = add(InsnNode(IALOAD))
val Block.laload: Unit get() = add(InsnNode(LALOAD))
val Block.faload: Unit get() = add(InsnNode(FALOAD))
val Block.daload: Unit get() = add(InsnNode(DALOAD))
val Block.aaload: Unit get() = add(InsnNode(AALOAD))
val Block.baload: Unit get() = add(InsnNode(BALOAD))
val Block.caload: Unit get() = add(InsnNode(CALOAD))
val Block.saload: Unit get() = add(InsnNode(SALOAD))
val Block.iastore: Unit get() = add(InsnNode(IASTORE))
val Block.lastore: Unit get() = add(InsnNode(LASTORE))
val Block.fastore: Unit get() = add(InsnNode(FASTORE))
val Block.dastore: Unit get() = add(InsnNode(DASTORE))
val Block.aastore: Unit get() = add(InsnNode(AASTORE))
val Block.bastore: Unit get() = add(InsnNode(BASTORE))
val Block.castore: Unit get() = add(InsnNode(CASTORE))
val Block.sastore: Unit get() = add(InsnNode(SASTORE))
val Block.arraylength: Unit get() = add(InsnNode(ARRAYLENGTH))
fun Block.anewarray(type: Type) = add(TypeInsnNode(ANEWARRAY, type.internalName))
fun Block.multianewarray(type: Type, dimensions: Int) = add(MultiANewArrayInsnNode(type.descriptor, dimensions))
fun Block.newarray(type: Type) {
  add(IntInsnNode(NEWARRAY, when (type.sort) {
    Type.BOOLEAN -> T_BOOLEAN
    Type.CHAR -> T_CHAR
    Type.BYTE -> T_BYTE
    Type.SHORT -> T_SHORT
    Type.INT -> T_INT
    Type.FLOAT -> T_FLOAT
    Type.LONG -> T_LONG
    Type.DOUBLE -> T_DOUBLE
    else -> error("Invalid type for primitive array creation")
  }))
}

// fields
fun Block.getstatic(owner: Type, name: String, type: Type) = add(FieldInsnNode(GETSTATIC, owner.internalName, name, type.descriptor))
fun Block.getfield(owner: Type, name: String, type: Type) = add(FieldInsnNode(GETFIELD, owner.internalName, name, type.descriptor))
fun Block.putstatic(owner: Type, name: String, type: Type) = add(FieldInsnNode(PUTSTATIC, owner.internalName, name, type.descriptor))
fun Block.putfield(owner: Type, name: String, type: Type) = add(FieldInsnNode(PUTFIELD, owner.internalName, name, type.descriptor))

// object management
fun Block.new(type: Type) = add(TypeInsnNode(NEW, type.internalName))
fun Block.checkcast(type: Type) = add(TypeInsnNode(CHECKCAST, type.internalName))
fun Block.instanceof(type: Type) = add(TypeInsnNode(INSTANCEOF, type.internalName))

// stack
val Block.pop: Unit get() = add(InsnNode(POP))
val Block.pop2: Unit get() = add(InsnNode(POP2))
val Block.dup: Unit get() = add(InsnNode(DUP))
val Block.dup_x1: Unit get() = add(InsnNode(DUP_X1))
val Block.dup_x2: Unit get() = add(InsnNode(DUP_X2))
val Block.dup2: Unit get() = add(InsnNode(DUP2))
val Block.dup2_x1: Unit get() = add(InsnNode(DUP2_X1))
val Block.dup2_x2: Unit get() = add(InsnNode(DUP2_X2))

fun Block.tableSwitch(min: Int, max: Int, defaultLabel: LabelNode, vararg labels: LabelNode) =
  add(TableSwitchInsnNode(min, max, defaultLabel, *labels))

fun Block.lookupSwitch(defaultLabel: LabelNode, vararg branches: Pair<Int, LabelNode>) =
  add(LookupSwitchInsnNode(defaultLabel,
    IntArray(branches.size) { branches[it].first },
    Array(branches.size) { branches[it].second }))

val Block.aconst_null: Unit get() = add(InsnNode(ACONST_NULL))
val Block.iconst_m1: Unit get() = add(InsnNode(ICONST_M1))
val Block.iconst_0: Unit get() = add(InsnNode(ICONST_0))
val Block.iconst_1: Unit get() = add(InsnNode(ICONST_1))
val Block.iconst_2: Unit get() = add(InsnNode(ICONST_2))
val Block.iconst_3: Unit get() = add(InsnNode(ICONST_3))
val Block.iconst_4: Unit get() = add(InsnNode(ICONST_4))
val Block.iconst_5: Unit get() = add(InsnNode(ICONST_5))
val Block.lconst_0: Unit get() = add(InsnNode(LCONST_0))
val Block.lconst_1: Unit get() = add(InsnNode(LCONST_1))
val Block.fconst_0: Unit get() = add(InsnNode(FCONST_0))
val Block.fconst_1: Unit get() = add(InsnNode(FCONST_1))
val Block.fconst_2: Unit get() = add(InsnNode(FCONST_2))
val Block.dconst_0: Unit get() = add(InsnNode(DCONST_0))
val Block.dconst_1: Unit get() = add(InsnNode(DCONST_1))
fun Block.bipush(v: Int) = add(IntInsnNode(BIPUSH, v))
fun Block.sipush(v: Int) = add(IntInsnNode(SIPUSH, v))
fun Block.ldc(v: Any) = add(LdcInsnNode(v))

fun Block.invokevirtual(owner: Type, name: String, returnType: Type, vararg parameterTypes: Type) =
  add(MethodInsnNode(INVOKEVIRTUAL, owner.internalName, name, Type.getMethodDescriptor(returnType, *parameterTypes)))
fun Block.invokespecial(owner: Type, name: String, returnType: Type, vararg parameterTypes: Type) =
  add(MethodInsnNode(INVOKESPECIAL, owner.internalName, name, Type.getMethodDescriptor(returnType, *parameterTypes)))
fun Block.invokestatic(owner: Type, name: String, returnType: Type, vararg parameterTypes: Type) =
  add(MethodInsnNode(INVOKESTATIC, owner.internalName, name, Type.getMethodDescriptor(returnType, *parameterTypes)))
fun Block.invokeinterface(owner: Type, name: String, returnType: Type, vararg parameterTypes: Type) =
  add(MethodInsnNode(INVOKEINTERFACE, owner.internalName, name, Type.getMethodDescriptor(returnType, *parameterTypes)))

val Block.ireturn: Unit get() = add(InsnNode(IRETURN))
val Block.lreturn: Unit get() = add(InsnNode(LRETURN))
val Block.freturn: Unit get() = add(InsnNode(FRETURN))
val Block.dreturn: Unit get() = add(InsnNode(DRETURN))
val Block.areturn: Unit get() = add(InsnNode(ARETURN))
val Block.`return`: Unit get() = add(InsnNode(RETURN))
val Block.lcmp: Unit get() = add(InsnNode(LCMP))
val Block.fcmpl: Unit get() = add(InsnNode(FCMPL))
val Block.fcmpg: Unit get() = add(InsnNode(FCMPG))
val Block.dcmpl: Unit get() = add(InsnNode(DCMPL))
val Block.dcmpg: Unit get() = add(InsnNode(DCMPG))
fun Block.ifeq(label: LabelNode) = add(JumpInsnNode(IFEQ, label))
fun Block.ifne(label: LabelNode) = add(JumpInsnNode(IFNE, label))
fun Block.iflt(label: LabelNode) = add(JumpInsnNode(IFLT, label))
fun Block.ifge(label: LabelNode) = add(JumpInsnNode(IFGE, label))
fun Block.ifgt(label: LabelNode) = add(JumpInsnNode(IFGT, label))
fun Block.ifle(label: LabelNode) = add(JumpInsnNode(IFLE, label))
fun Block.if_icmpeq(label: LabelNode) = add(JumpInsnNode(IF_ICMPEQ, label))
fun Block.if_icmpne(label: LabelNode) = add(JumpInsnNode(IF_ICMPNE, label))
fun Block.if_icmplt(label: LabelNode) = add(JumpInsnNode(IF_ICMPLT, label))
fun Block.if_icmpge(label: LabelNode) = add(JumpInsnNode(IF_ICMPGE, label))
fun Block.if_icmpgt(label: LabelNode) = add(JumpInsnNode(IF_ICMPGT, label))
fun Block.if_icmple(label: LabelNode) = add(JumpInsnNode(IF_ICMPLE, label))
fun Block.if_acmpeq(label: LabelNode) = add(JumpInsnNode(IF_ACMPEQ, label))
fun Block.if_acmpne(label: LabelNode) = add(JumpInsnNode(IF_ACMPNE, label))
fun Block.goto(label: LabelNode) = add(JumpInsnNode(GOTO, label))
fun Block.ifnull(label: LabelNode) = add(JumpInsnNode(IFNULL, label))
fun Block.ifnonnull(label: LabelNode) = add(JumpInsnNode(IFNONNULL, label))
fun Block.jsr(label: LabelNode) = add(JumpInsnNode(JSR, label))
fun Block.ret(slot: Int) = add(VarInsnNode(RET, slot))
val Block.athrow: Unit get() = add(InsnNode(ATHROW))
