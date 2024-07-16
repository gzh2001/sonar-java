package org.sonar.samples.java.checks;

import org.junit.jupiter.api.Test;
import org.sonar.java.checks.verifier.CheckVerifier;

public class HardEncodedWebURICheckTest {
  @Test
  void test() {
    CheckVerifier.newVerifier()
      .onFile("src/test/files/HardEncodedWebURIDemo.java")
      .withCheck(new HardEncodedWebURICheck())
      .verifyIssues();
  }
}
