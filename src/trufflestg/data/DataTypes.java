package trufflestg.data;

import com.oracle.truffle.api.dsl.TypeSystem;

// Interesting runtime types
// TODO
@TypeSystem({
  Closure.class,
  Thunk.class,
  DataCon.class,
  StgArray.class,
  VoidInh.class,
  StgInt.class
})
public abstract class DataTypes {
//  @ImplicitCast
//  @CompilerDirectives.TruffleBoundary
//  public static BigInt castBigNumber(int value) { return new BigInt(value); }
//
//  @TypeCheck(Unit.class)
//  public static boolean isUnit(Object value) { return value == Unit.INSTANCE; }
//
//  @SuppressWarnings("SameReturnValue")
//  @TypeCast(Unit.class)
//  public static Unit asUnit(Object value) {
//    assert value == Unit.INSTANCE;
//    return Unit.INSTANCE;
//  }
}