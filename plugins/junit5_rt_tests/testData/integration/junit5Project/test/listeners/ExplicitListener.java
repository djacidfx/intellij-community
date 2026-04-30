package listeners;

import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;

public class ExplicitListener implements TestExecutionListener {
  @Override
  public void executionStarted(TestIdentifier testIdentifier) {
    if (testIdentifier.isTest()) {
      System.out.println("explicit-listener=" + testIdentifier.getDisplayName());
    }
  }
}
