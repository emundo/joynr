/*
 * #%L
 * %%
 * Copyright (C) 2019 BMW Car IT GmbH
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package io.joynr.test.interlanguage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.runner.JUnitCore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.joynr.test.interlanguage.testresults.TestResult;
import io.joynr.test.interlanguage.testresults.TestSuiteResult;

public class IltConsumerTestStarter {
    private static final Logger LOG = LoggerFactory.getLogger(IltConsumerTestStarter.class);

    public static void main(String argv[]) {
        IltConsumerTestStarter t = new IltConsumerTestStarter();
        boolean testSucceeded = t.runTests();
        if (!testSucceeded) {
            System.exit(1);
        }
        System.exit(0);
    }

    public boolean runTests() {
        TestResult testResult;
        boolean result;

        JUnitCore runner = new JUnitCore();
        IltConsumerJUnitListener listener = new IltConsumerJUnitListener();
        runner.addListener(listener);
        runner.run(IltConsumerTestSuite.class);
        testResult = listener.getTestResult();
        ObjectMapper mapper = new ObjectMapper();
        try {
            String jsonString = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(testResult);
            LOG.info("runTests: serialized test results:");
            LOG.info(jsonString);
        } catch (Exception e) {
            // ignore
            LOG.info("runTests: failed to serialize test results");
            LOG.info("runTests: TEST FAILED");
            return false;
        }

        int errors = 0;
        int failures = 0;

        for (TestSuiteResult testSuiteResult : testResult.getTestSuiteResults()) {
            errors += testSuiteResult.getErrors();
            failures += testSuiteResult.getFailures();
        }

        LOG.info("runTests: #errors: " + errors + ", #failures: " + failures);
        if (errors > 0 || failures > 0) {
            LOG.info("runTests: TEST FAILED");
            return false;
        }
        LOG.info("runTests: TEST SUCCEEDED");
        return true;
    }
}