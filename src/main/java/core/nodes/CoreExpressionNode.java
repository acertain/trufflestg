package core.nodes;

import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.GenerateWrapper;
import com.oracle.truffle.api.instrumentation.InstrumentableNode;
import com.oracle.truffle.api.instrumentation.ProbeNode;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import java.math.BigInteger;

@GenerateWrapper
@NodeInfo(language = "core", description = "The abstract base node for all core expressions")
public abstract class CoreExpressionNode extends CoreNode {
  public boolean hasTag(Class<? extends Tag> tag) {
    if (tag == StandardTags.ExpressionTag.class) return true; 
    return false;
  }

  public WrapperNode createWrapper(ProbeNode probe) {
    return new CoreExpressionNodeWrapper(this, probe);
  }
}