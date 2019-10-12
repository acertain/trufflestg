package core.node;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExecutableNode;
import com.oracle.truffle.api.nodes.Node;
import core.CoreLanguage;
import core.node.expr.Expression;

// returned in response to an InlineParsingRequest. Not otherwise used.
public class CoreExecutableNode extends ExecutableNode {
  @Node.Child public Expression body;

  protected CoreExecutableNode(CoreLanguage language, Expression body) {
    super(language);
    this.body = body;
  }

  public Object execute(VirtualFrame frame) {
    return body.execute(frame);
  }

  public static CoreExecutableNode create(CoreLanguage language, Expression body) {
    return new CoreExecutableNode(language, body);
  }
}
