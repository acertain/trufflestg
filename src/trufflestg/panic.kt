package trufflestg

import com.oracle.truffle.api.CompilerDirectives
import org.intelligence.diagnostics.Severity
import org.intelligence.diagnostics.error
import org.intelligence.pretty.Pretty

private inline fun <reified T> Array<T>.trim(i : Int = 1): Array<T> = this.drop(i).toTypedArray()

internal class Panic(message: String? = null) : RuntimeException(message) {
  internal constructor(message: String? = null, cause: Throwable?): this(message) {
    initCause(cause)
  }
  companion object { const val serialVersionUID : Long = 1L }
  override fun toString(): String = stackTrace?.getOrNull(0)?.let {
    Pretty.ppString {
      error(Severity.panic, it.fileName, it.lineNumber, null, null, message)
    }
  } ?: super.toString()
}

fun panic(msg: String, base: Throwable?): Nothing {
  // truffle uses transferToInterpreterAndInvalidate in CompilerDirectives.shouldNotReachHere
  CompilerDirectives.transferToInterpreterAndInvalidate()
  throw Panic(msg, base).also { it.stackTrace = it.stackTrace.trim() }
}

@Suppress("unused")
fun panic(msg: String): Nothing {
  CompilerDirectives.transferToInterpreterAndInvalidate()
  throw Panic(msg).also { it.stackTrace = it.stackTrace.trim() }
}

fun panic(msg: () -> String): Nothing {
  CompilerDirectives.transferToInterpreterAndInvalidate()
  throw Panic(msg()).also { it.stackTrace = it.stackTrace.trim() }
}

internal class TODOExpcetion() : RuntimeException() {
  companion object { const val serialVersionUID : Long = 1L }
  override fun toString(): String =
    stackTrace?.getOrNull(0)?.let {
      Pretty.ppString {
        error(Severity.todo, it.fileName, it.lineNumber, null, null, it.methodName)
      }
    } ?: super.toString()
}

fun TODO(): Nothing { todo }

val todo: Nothing get() {
  CompilerDirectives.transferToInterpreterAndInvalidate()
  throw TODOExpcetion().also { it.stackTrace = it.stackTrace.trim() }
}

