package org.intelligence.asm

import org.objectweb.asm.Type
import kotlin.reflect.KClass

// this lets you use +String::class as a Type for convenience.
operator fun KClass<*>.unaryPlus(): Type = type(this)
operator fun Class<*>.unaryPlus(): Type = type(this)

val void: Type get() = Type.VOID_TYPE
val char: Type get() = Type.CHAR_TYPE
val byte: Type get() = Type.BYTE_TYPE
val int: Type get() = Type.INT_TYPE
val float: Type get() = Type.FLOAT_TYPE
val long: Type get() = Type.LONG_TYPE
val double: Type get() = Type.DOUBLE_TYPE
val boolean: Type get() = Type.BOOLEAN_TYPE
fun type(k: KClass<*>): Type = Type.getType(k.java)
fun type(k: Class<*>): Type = Type.getType(k)
fun type(t: String): Type = Type.getObjectType(t)
val Type.array: Type get() = Type.getType("[$descriptor")
val string: Type get() = +String::class
val `object`: Type get() = +Object::class
val `class`: Type get() = +Class::class

