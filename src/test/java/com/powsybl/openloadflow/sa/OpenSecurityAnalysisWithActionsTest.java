/**
 * Copyright (c) 2023, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.sa;

import com.powsybl.commons.reporter.Reporter;
import com.powsybl.commons.reporter.ReporterModel;
import com.powsybl.contingency.*;
import com.powsybl.iidm.network.*;
import com.powsybl.iidm.network.extensions.HvdcAngleDroopActivePowerControlAdder;
import com.powsybl.iidm.serde.test.MetrixTutorialSixBusesFactory;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.graph.GraphConnectivityFactory;
import com.powsybl.openloadflow.graph.NaiveGraphConnectivityFactory;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.util.LoadFlowAssert;
import com.powsybl.security.LimitViolation;
import com.powsybl.security.LimitViolationType;
import com.powsybl.security.SecurityAnalysisParameters;
import com.powsybl.security.SecurityAnalysisResult;
import com.powsybl.security.action.*;
import com.powsybl.security.condition.AllViolationCondition;
import com.powsybl.security.condition.AnyViolationCondition;
import com.powsybl.security.condition.AtLeastOneViolationCondition;
import com.powsybl.security.condition.TrueCondition;
import com.powsybl.security.monitor.StateMonitor;
import com.powsybl.security.results.BranchResult;
import com.powsybl.security.results.OperatorStrategyResult;
import com.powsybl.security.results.PostContingencyResult;
import com.powsybl.security.results.PreContingencyResult;
import com.powsybl.security.strategy.OperatorStrategy;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.powsybl.openloadflow.util.LoadFlowAssert.assertReportEquals;
import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 * @author Anne Tilloy {@literal <anne.tilloy at rte-france.com>}
 */
class OpenSecurityAnalysisWithActionsTest extends AbstractOpenSecurityAnalysisTest {

    @Test
    void testDcEquationSystemUpdater() {
        Network network = VoltageControlNetworkFactory.createWithShuntSharedRemoteControl();

        LoadFlowParameters lfParameters = new LoadFlowParameters().setDc(true);
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.setLoadFlowParameters(lfParameters);

        String id = network.getTwoWindingsTransformer("tr2").getId();
        List<Contingency> contingencies = List.of(new Contingency(id, new TwoWindingsTransformerContingency(id)));

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        List<Action> actions = List.of(new LoadActionBuilder().withId("action").withLoadId("l4").withRelativeValue(false).withActivePowerValue(260).build());
        List<OperatorStrategy> operatorStrategies = List.of(new OperatorStrategy("strategy", ContingencyContext.specificContingency("tr2"), new TrueCondition(), List.of("action")));

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters, operatorStrategies, actions, Reporter.NO_OP);
        assertFalse(result.getPostContingencyResults().isEmpty());
    }

    @Test
    void testSecurityAnalysisReport() throws IOException {
        Network network = createNodeBreakerNetwork();
        network.getLine("L1").getCurrentLimits1().ifPresent(limits -> limits.setPermanentLimit(200));

        List<Contingency> contingencies = List.of(new Contingency("L2", new BranchContingency("L2")));

        LoadFlowParameters lfParameters = new LoadFlowParameters();
        OpenLoadFlowParameters openLoadFlowParameters = new OpenLoadFlowParameters();
        openLoadFlowParameters.setReportedFeatures(Set.of(OpenLoadFlowParameters.ReportedFeatures.NEWTON_RAPHSON_SECURITY_ANALYSIS));
        lfParameters.addExtension(OpenLoadFlowParameters.class, openLoadFlowParameters);

        SecurityAnalysisParameters saParameters = new SecurityAnalysisParameters();
        saParameters.setLoadFlowParameters(lfParameters);

        ReporterModel reporter = new ReporterModel("testSaReport", "Test report of security analysis");
        runSecurityAnalysis(network, contingencies, Collections.emptyList(),
                saParameters, Collections.emptyList(), Collections.emptyList(), reporter);

        assertReportEquals("/detailedNrReportSecurityAnalysis.txt", reporter);
    }

    @Test
    void testSecurityAnalysisWithOperatorStrategy() {
        GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory = new NaiveGraphConnectivityFactory<>(LfBus::getNum);
        securityAnalysisProvider = new OpenSecurityAnalysisProvider(matrixFactory, connectivityFactory);

        Network network = NodeBreakerNetworkFactory.create3Bars();
        network.getSwitch("C1").setOpen(true);
        network.getSwitch("C2").setOpen(true);
        network.getLineStream().forEach(line -> {
            if (line.getCurrentLimits1().isPresent()) {
                line.getCurrentLimits1().orElseThrow().setPermanentLimit(310);
            }
            if (line.getCurrentLimits2().isPresent()) {
                line.getCurrentLimits2().orElseThrow().setPermanentLimit(310);
            }
        });

        List<Contingency> contingencies = Stream.of("L1", "L3", "L2")
                .map(id -> new Contingency(id, new BranchContingency(id)))
                .collect(Collectors.toList());

        List<Action> actions = List.of(new SwitchAction("action1", "C1", false),
                                       new SwitchAction("action3", "C2", false));

        List<OperatorStrategy> operatorStrategies = List.of(new OperatorStrategy("strategyL1", ContingencyContext.specificContingency("L1"), new TrueCondition(), List.of("action1")),
                                                            new OperatorStrategy("strategyL3", ContingencyContext.specificContingency("L3"), new TrueCondition(), List.of("action3")),
                                                            new OperatorStrategy("strategyL2", ContingencyContext.specificContingency("L2"), new TrueCondition(), List.of("action1", "action3")));

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        LoadFlowParameters parameters = new LoadFlowParameters();
        parameters.setDistributedSlack(false);
        setSlackBusId(parameters, "VL2_0");
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.setLoadFlowParameters(parameters);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters,
                operatorStrategies, actions, Reporter.NO_OP);
        assertEquals(578.740, result.getPreContingencyResult().getNetworkResult().getBranchResult("L1").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(0.0, result.getPreContingencyResult().getNetworkResult().getBranchResult("L2").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(292.708, result.getPreContingencyResult().getNetworkResult().getBranchResult("L3").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(0.002, getPostContingencyResult(result, "L1").getNetworkResult().getBranchResult("L2").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(318.294, getPostContingencyResult(result, "L1").getNetworkResult().getBranchResult("L3").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(583.624, getOperatorStrategyResult(result, "strategyL1").getNetworkResult().getBranchResult("L2").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(303.513, getOperatorStrategyResult(result, "strategyL1").getNetworkResult().getBranchResult("L3").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(0.0, getPostContingencyResult(result, "L3").getNetworkResult().getBranchResult("L2").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(602.965, getPostContingencyResult(result, "L3").getNetworkResult().getBranchResult("L1").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(303.513, getOperatorStrategyResult(result, "strategyL3").getNetworkResult().getBranchResult("L2").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(583.624, getOperatorStrategyResult(result, "strategyL3").getNetworkResult().getBranchResult("L1").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(583.624, getPostContingencyResult(result, "L2").getNetworkResult().getBranchResult("L1").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(303.513, getPostContingencyResult(result, "L2").getNetworkResult().getBranchResult("L3").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(441.539, getOperatorStrategyResult(result, "strategyL2").getNetworkResult().getBranchResult("L1").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(441.539, getOperatorStrategyResult(result, "strategyL2").getNetworkResult().getBranchResult("L3").getI1(), LoadFlowAssert.DELTA_I);

        // re-run with loadflows
        loadFlowRunner.run(network, parameters);
        assertEquals(578.740, network.getLine("L1").getTerminal1().getI(), LoadFlowAssert.DELTA_I);
        assertEquals(0.0, network.getLine("L2").getTerminal1().getI(), LoadFlowAssert.DELTA_I);
        assertEquals(292.708, network.getLine("L3").getTerminal1().getI(), LoadFlowAssert.DELTA_I);

        network.getLine("L1").getTerminal1().disconnect();
        network.getLine("L1").getTerminal2().disconnect();
        loadFlowRunner.run(network, parameters);
        assertEquals(0.002, network.getLine("L2").getTerminal1().getI(), LoadFlowAssert.DELTA_I);
        assertEquals(318.284, network.getLine("L3").getTerminal1().getI(), LoadFlowAssert.DELTA_I);

        network.getSwitch("C1").setOpen(false);
        loadFlowRunner.run(network, parameters);
        assertEquals(583.624, network.getLine("L2").getTerminal1().getI(), LoadFlowAssert.DELTA_I);
        assertEquals(303.513, network.getLine("L3").getTerminal1().getI(), LoadFlowAssert.DELTA_I);

        network.getLine("L1").getTerminal1().connect();
        network.getLine("L1").getTerminal2().connect();
        network.getLine("L3").getTerminal1().disconnect();
        network.getLine("L3").getTerminal2().disconnect();
        network.getSwitch("C1").setOpen(true);
        loadFlowRunner.run(network, parameters);
        assertEquals(602.965, network.getLine("L1").getTerminal1().getI(), LoadFlowAssert.DELTA_I);
        assertEquals(0.0, network.getLine("L2").getTerminal1().getI(), LoadFlowAssert.DELTA_I);

        network.getSwitch("C2").setOpen(false);
        loadFlowRunner.run(network, parameters);
        assertEquals(583.624, network.getLine("L1").getTerminal1().getI(), LoadFlowAssert.DELTA_I);
        assertEquals(303.513, network.getLine("L2").getTerminal1().getI(), LoadFlowAssert.DELTA_I);

        network.getLine("L3").getTerminal1().connect();
        network.getLine("L3").getTerminal2().connect();
        network.getLine("L2").getTerminal1().disconnect();
        network.getLine("L2").getTerminal2().disconnect();
        network.getSwitch("C2").setOpen(true);
        loadFlowRunner.run(network, parameters);
        assertEquals(583.624, network.getLine("L1").getTerminal1().getI(), LoadFlowAssert.DELTA_I);
        assertEquals(303.513, network.getLine("L3").getTerminal1().getI(), LoadFlowAssert.DELTA_I);

        network.getSwitch("C1").setOpen(false);
        network.getSwitch("C2").setOpen(false);
        loadFlowRunner.run(network, parameters);
        assertEquals(441.539, network.getLine("L1").getTerminal1().getI(), LoadFlowAssert.DELTA_I);
        assertEquals(89.429, network.getLine("L2").getTerminal1().getI(), LoadFlowAssert.DELTA_I);
        assertEquals(441.539, network.getLine("L3").getTerminal1().getI(), LoadFlowAssert.DELTA_I);
    }

    @Test
    void testSecurityAnalysisWithOperatorStrategy2() {
        GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory = new NaiveGraphConnectivityFactory<>(LfBus::getNum);
        securityAnalysisProvider = new OpenSecurityAnalysisProvider(matrixFactory, connectivityFactory);

        Network network = NodeBreakerNetworkFactory.create3Bars();
        network.getSwitch("C1").setOpen(true);
        network.getSwitch("C2").setOpen(true);
        network.getLine("L1").getCurrentLimits1().orElseThrow().setPermanentLimit(580.0);
        network.getLine("L1").getCurrentLimits2().orElseThrow().setPermanentLimit(580.0);
        network.getLine("L2").getCurrentLimits1().orElseThrow().setPermanentLimit(500.0);
        network.getLine("L2").getCurrentLimits2().orElseThrow().setPermanentLimit(500.0);

        List<Contingency> contingencies = Stream.of("L1", "L3", "L2")
                .map(id -> new Contingency(id, new BranchContingency(id)))
                .collect(Collectors.toList());

        List<Action> actions = List.of(new SwitchAction("action1", "C1", false),
                                       new SwitchAction("action3", "C2", false));

        List<OperatorStrategy> operatorStrategies = List.of(new OperatorStrategy("strategyL1", ContingencyContext.specificContingency("L1"), new AnyViolationCondition(), List.of("action1")),
                                                            new OperatorStrategy("strategyL3", ContingencyContext.specificContingency("L3"), new AnyViolationCondition(), List.of("action3")),
                                                            new OperatorStrategy("strategyL2_1", ContingencyContext.specificContingency("L2"), new AtLeastOneViolationCondition(List.of("L1")), List.of("action1", "action3")),
                                                            new OperatorStrategy("strategyL2_2", ContingencyContext.specificContingency("L2"), new AllViolationCondition(List.of("L1")), List.of("action1", "action3")));

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        LoadFlowParameters parameters = new LoadFlowParameters();
        parameters.setDistributedSlack(false);
        setSlackBusId(parameters, "VL2_0");
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.setLoadFlowParameters(parameters);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters,
                operatorStrategies, actions, Reporter.NO_OP);
        assertEquals(578.740, result.getPreContingencyResult().getNetworkResult().getBranchResult("L1").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(0.0, result.getPreContingencyResult().getNetworkResult().getBranchResult("L2").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(292.708, result.getPreContingencyResult().getNetworkResult().getBranchResult("L3").getI1(), LoadFlowAssert.DELTA_I);
        // L1 contingency
        assertEquals(0.0, getPostContingencyResult(result, "L1").getNetworkResult().getBranchResult("L2").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(318.284, getPostContingencyResult(result, "L1").getNetworkResult().getBranchResult("L3").getI1(), LoadFlowAssert.DELTA_I);
        assertTrue(getPostContingencyResult(result, "L1").getLimitViolationsResult().getLimitViolations().isEmpty());
        assertTrue(getOptionalOperatorStrategyResult(result, "strategyL1").isEmpty());
        // L3 contingency
        assertEquals(0.0, getPostContingencyResult(result, "L3").getNetworkResult().getBranchResult("L2").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(602.965, getPostContingencyResult(result, "L3").getNetworkResult().getBranchResult("L1").getI1(), LoadFlowAssert.DELTA_I);
        assertFalse(getPostContingencyResult(result, "L3").getLimitViolationsResult().getLimitViolations().isEmpty()); // HIGH_VOLTAGE
        assertFalse(getOptionalOperatorStrategyResult(result, "strategyL3").isEmpty());
        // L2 contingency
        assertEquals(583.624, getPostContingencyResult(result, "L2").getNetworkResult().getBranchResult("L1").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(303.513, getPostContingencyResult(result, "L2").getNetworkResult().getBranchResult("L3").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(441.539, getOperatorStrategyResult(result, "strategyL2_1").getNetworkResult().getBranchResult("L1").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(441.539, getOperatorStrategyResult(result, "strategyL2_2").getNetworkResult().getBranchResult("L1").getI1(), LoadFlowAssert.DELTA_I);
    }

    @Test
    void testSecurityAnalysisWithOperatorStrategy3() {
        GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory = new NaiveGraphConnectivityFactory<>(LfBus::getNum);
        securityAnalysisProvider = new OpenSecurityAnalysisProvider(matrixFactory, connectivityFactory);

        Network network = NodeBreakerNetworkFactory.create3Bars();
        network.getVoltageLevel("VL1").setLowVoltageLimit(390.0);
        network.getVoltageLevel("VL2").setLowVoltageLimit(390.0);
        network.getSwitch("C1").setOpen(true);
        network.getSwitch("C2").setOpen(true);
        network.getLine("L1").getCurrentLimits1().orElseThrow().setPermanentLimit(580.0);
        network.getLine("L1").getCurrentLimits2().orElseThrow().setPermanentLimit(580.0);
        network.getLine("L2").getCurrentLimits1().orElseThrow().setPermanentLimit(500.0);
        network.getLine("L2").getCurrentLimits2().orElseThrow().setPermanentLimit(500.0);

        List<Contingency> contingencies = Stream.of("L3", "L2")
                .map(id -> new Contingency(id, new BranchContingency(id)))
                .collect(Collectors.toList());

        List<Action> actions = List.of(new SwitchAction("action1", "C1", false),
                                       new SwitchAction("action3", "C2", false));

        List<OperatorStrategy> operatorStrategies = List.of(new OperatorStrategy("strategyL3", ContingencyContext.specificContingency("L3"), new AllViolationCondition(List.of("VL1", "VL2")), List.of("action3")),
                                                            new OperatorStrategy("strategyL2", ContingencyContext.specificContingency("L2"), new AtLeastOneViolationCondition(List.of("L1", "L3")), List.of("action1", "action3")));

        List<StateMonitor> monitors = createNetworkMonitors(network);

        LoadFlowParameters parameters = new LoadFlowParameters();
        parameters.setDistributedSlack(false);
        setSlackBusId(parameters, "VL2_0");
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.setLoadFlowParameters(parameters);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters,
                operatorStrategies, actions, Reporter.NO_OP);
        // L3 contingency
        assertFalse(getOptionalOperatorStrategyResult(result, "strategyL3").isEmpty());
        // L2 contingency
        assertFalse(getOptionalOperatorStrategyResult(result, "strategyL2").isEmpty());
    }

    @Test
    void testWithSeveralConnectedComponents() {
        GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory = new NaiveGraphConnectivityFactory<>(LfBus::getNum);
        securityAnalysisProvider = new OpenSecurityAnalysisProvider(matrixFactory, connectivityFactory);

        Network network = ConnectedComponentNetworkFactory.createTwoCcLinkedBySwitches();

        List<Contingency> contingencies = Stream.of("s25")
                .map(id -> new Contingency(id, new SwitchContingency(id)))
                .collect(Collectors.toList());

        List<Action> actions = List.of(new SwitchAction("action1", "s34", true));

        List<OperatorStrategy> operatorStrategies = List.of(new OperatorStrategy("strategyS25", ContingencyContext.specificContingency("s25"), new TrueCondition(), List.of("action1")));

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, new SecurityAnalysisParameters(),
                operatorStrategies, actions, Reporter.NO_OP);
        assertEquals(1.255, result.getPreContingencyResult().getNetworkResult().getBranchResult("l12").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(1.745, result.getPreContingencyResult().getNetworkResult().getBranchResult("l13").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.502, result.getPreContingencyResult().getNetworkResult().getBranchResult("l23").getP1(), LoadFlowAssert.DELTA_POWER);
        // s25 contingency
        assertEquals(1.332, getPostContingencyResult(result, "s25").getNetworkResult().getBranchResult("l12").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(1.667, getPostContingencyResult(result, "s25").getNetworkResult().getBranchResult("l13").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.335, getPostContingencyResult(result, "s25").getNetworkResult().getBranchResult("l23").getP1(), LoadFlowAssert.DELTA_POWER);
        // strategyS25 operator strategy
        assertEquals(0.666, getOperatorStrategyResult(result, "strategyS25").getNetworkResult().getBranchResult("l12").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.333, getOperatorStrategyResult(result, "strategyS25").getNetworkResult().getBranchResult("l13").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.333, getOperatorStrategyResult(result, "strategyS25").getNetworkResult().getBranchResult("l23").getP1(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testMetrixTutorial() {
        GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory = new NaiveGraphConnectivityFactory<>(LfBus::getNum);
        securityAnalysisProvider = new OpenSecurityAnalysisProvider(matrixFactory, connectivityFactory);

        Network network = MetrixTutorialSixBusesFactory.create();
        network.getGenerator("SO_G2").setTargetP(960);

        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        LoadFlowParameters parameters = new LoadFlowParameters();
        parameters.setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD);
        parameters.setHvdcAcEmulation(false);
        securityAnalysisParameters.setLoadFlowParameters(parameters);

        List<Contingency> contingencies = List.of(new Contingency("S_SO_1", new BranchContingency("S_SO_1")));

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        List<Action> actions = List.of(new SwitchAction("openSwitchS0", "SOO1_SOO1_DJ_OMN", true),
                                       new LineConnectionAction("openLineSSO2", "S_SO_2", true, true),
                                       new PhaseTapChangerTapPositionAction("pst", "NE_NO_1", false, 1), // PST at tap position 17.
                                       new PhaseTapChangerTapPositionAction("pst2", "NE_NO_1", true, -16));
        List<OperatorStrategy> operatorStrategies = List.of(new OperatorStrategy("strategy1", ContingencyContext.specificContingency("S_SO_1"), new AllViolationCondition(List.of("S_SO_2")), List.of("openSwitchS0")),
                                                            new OperatorStrategy("strategy2", ContingencyContext.specificContingency("S_SO_1"), new AllViolationCondition(List.of("S_SO_2")), List.of("openLineSSO2")),
                                                            new OperatorStrategy("strategy3", ContingencyContext.specificContingency("S_SO_1"), new TrueCondition(), List.of("pst")),
                                                            new OperatorStrategy("strategy4", ContingencyContext.specificContingency("S_SO_1"), new TrueCondition(), List.of("pst2")));

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters,
                operatorStrategies, actions, Reporter.NO_OP);
        assertEquals(346.296, result.getPreContingencyResult().getNetworkResult().getBranchResult("S_SO_2").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(642.805, getPostContingencyResult(result, "S_SO_1").getNetworkResult().getBranchResult("S_SO_2").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(240.523, getOperatorStrategyResult(result, "strategy1").getNetworkResult().getBranchResult("S_SO_2").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(599.161, getOperatorStrategyResult(result, "strategy2").getNetworkResult().getBranchResult("SO_NO_1").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(639.268, getOperatorStrategyResult(result, "strategy3").getNetworkResult().getBranchResult("S_SO_2").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(639.268, getOperatorStrategyResult(result, "strategy4").getNetworkResult().getBranchResult("S_SO_2").getI1(), LoadFlowAssert.DELTA_I);

        loadFlowRunner.run(network, parameters);
        assertEquals(346.296, network.getLine("S_SO_2").getTerminal1().getI(), LoadFlowAssert.DELTA_I);

        network.getLine("S_SO_1").getTerminal1().disconnect();
        network.getLine("S_SO_1").getTerminal2().disconnect();
        loadFlowRunner.run(network, parameters);
        assertEquals(642.805, network.getLine("S_SO_2").getTerminal1().getI(), LoadFlowAssert.DELTA_I);

        network.getSwitch("SOO1_SOO1_DJ_OMN").setOpen(true);
        OpenLoadFlowParameters.create(parameters)
                .setMaxOuterLoopIterations(50);
        loadFlowRunner.run(network, parameters);
        assertEquals(240.523, network.getLine("S_SO_2").getTerminal1().getI(), LoadFlowAssert.DELTA_I);

        network.getSwitch("SOO1_SOO1_DJ_OMN").setOpen(false);
        network.getTwoWindingsTransformer("NE_NO_1").getPhaseTapChanger().setTapPosition(1);
        loadFlowRunner.run(network, parameters);
        assertEquals(639.268, network.getLine("S_SO_2").getTerminal1().getI(), LoadFlowAssert.DELTA_I);
    }

    @Test
    void testMetrixCurrent() {
        GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory = new NaiveGraphConnectivityFactory<>(LfBus::getNum);
        securityAnalysisProvider = new OpenSecurityAnalysisProvider(matrixFactory, connectivityFactory);

        Network network = MetrixTutorialSixBusesSecurityAnalysisFactory.createWithCurrentLimits();

        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        LoadFlowParameters parameters = new LoadFlowParameters();
        parameters.setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD);
        parameters.setHvdcAcEmulation(false);
        securityAnalysisParameters.setLoadFlowParameters(parameters);

        List<Contingency> contingencies = List.of(new Contingency("branch_S_SO_1", new BranchContingency("S_SO_1")));

        List<StateMonitor> monitors = createNetworkMonitors(network);

        List<Action> actions = List.of(new SwitchAction("openSwitch", "SS1_SS1_DJ_OMN", true),
                                       new LineConnectionAction("openLineSSO2", "S_SO_2", true, true),
                                       new PhaseTapChangerTapPositionAction("pstChangeTap", "NE_NO_1", false, 8));
        List<OperatorStrategy> operatorStrategies = List.of(new OperatorStrategy("strategy1", ContingencyContext.specificContingency("branch_S_SO_1"), new AllViolationCondition(List.of("S_SO_2")), List.of("openSwitch")),
                                                            new OperatorStrategy("strategy2", ContingencyContext.specificContingency("branch_S_SO_1"), new AllViolationCondition(List.of("S_SO_2")), List.of("openLineSSO2")),
                                                            new OperatorStrategy("strategy3", ContingencyContext.specificContingency("branch_S_SO_1"), new AllViolationCondition(List.of("S_SO_2")), List.of("pstChangeTap")));

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters,
                operatorStrategies, actions, Reporter.NO_OP);

        PreContingencyResult preContingencyResult = result.getPreContingencyResult();
        assertEquals(0, preContingencyResult.getLimitViolationsResult().getLimitViolations().size());

        PostContingencyResult postContingencyResult = getPostContingencyResult(result, "branch_S_SO_1");
        assertEquals(2, postContingencyResult.getLimitViolationsResult().getLimitViolations().size());

        for (LimitViolation limitViolation : postContingencyResult.getLimitViolationsResult().getLimitViolations()) {
            assertEquals("S_SO_2", limitViolation.getSubjectId());
            assertEquals(LimitViolationType.CURRENT, limitViolation.getLimitType());
        }

        String[] operatorStrategiesString = {"strategy1", "strategy2", "strategy3"};
        for (String operatorStrategyString : operatorStrategiesString) {
            OperatorStrategyResult operatorStrategyResult = getOperatorStrategyResult(result, operatorStrategyString);
            assertEquals(0, operatorStrategyResult.getLimitViolationsResult().getLimitViolations().size());
        }
    }

    @Test
    void testMetrixVoltage() {
        GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory = new NaiveGraphConnectivityFactory<>(LfBus::getNum);
        securityAnalysisProvider = new OpenSecurityAnalysisProvider(matrixFactory, connectivityFactory);

        Network network = MetrixTutorialSixBusesSecurityAnalysisFactory.createWithCurrentLimits2();

        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        LoadFlowParameters parameters = new LoadFlowParameters();
        parameters.setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD);
        parameters.setHvdcAcEmulation(false);
        securityAnalysisParameters.setLoadFlowParameters(parameters);

        List<Contingency> contingencies = List.of(new Contingency("branch_S_SE_1", new BranchContingency("S_SE_1")));

        List<StateMonitor> monitors = createNetworkMonitors(network);

        List<Action> actions = List.of(new LoadActionBuilder().withId("loadAction").withLoadId("SE_L1").withRelativeValue(false).withActivePowerValue(0.0).build(),
                                       new GeneratorActionBuilder().withId("generatorAction").withGeneratorId("N_G").withActivePowerRelativeValue(false).withActivePowerValue(400.0).build());
        List<OperatorStrategy> operatorStrategies = List.of(new OperatorStrategy("strategy1", ContingencyContext.specificContingency("branch_S_SE_1"), new AllViolationCondition(List.of("SE_poste")), List.of("loadAction")),
                                                            new OperatorStrategy("strategy2", ContingencyContext.specificContingency("branch_S_SE_1"), new AllViolationCondition(List.of("SE_poste")), List.of("generatorAction")));

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters,
                operatorStrategies, actions, Reporter.NO_OP);

        PreContingencyResult preContingencyResult = result.getPreContingencyResult();
        assertEquals(0, preContingencyResult.getLimitViolationsResult().getLimitViolations().size());

        PostContingencyResult postContingencyResult = getPostContingencyResult(result, "branch_S_SE_1");
        assertEquals(1, postContingencyResult.getLimitViolationsResult().getLimitViolations().size());

        for (LimitViolation limitViolation : postContingencyResult.getLimitViolationsResult().getLimitViolations()) {
            assertEquals("SE_poste", limitViolation.getSubjectId());
            assertEquals(LimitViolationType.LOW_VOLTAGE, limitViolation.getLimitType());
        }

        OperatorStrategyResult operatorStrategyResult = getOperatorStrategyResult(result, "strategy1");
        assertEquals(0, operatorStrategyResult.getLimitViolationsResult().getLimitViolations().size());

        OperatorStrategyResult operatorStrategyResult2 = getOperatorStrategyResult(result, "strategy2");
        assertEquals(0, operatorStrategyResult2.getLimitViolationsResult().getLimitViolations().size());
    }

    @Test
    void testMetrixActivePower() {
        GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory = new NaiveGraphConnectivityFactory<>(LfBus::getNum);
        securityAnalysisProvider = new OpenSecurityAnalysisProvider(matrixFactory, connectivityFactory);

        Network network = MetrixTutorialSixBusesSecurityAnalysisFactory.createWithActivePowerLimits();

        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        LoadFlowParameters parameters = new LoadFlowParameters();
        parameters.setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD);
        parameters.setHvdcAcEmulation(false);
        securityAnalysisParameters.setLoadFlowParameters(parameters);

        List<Contingency> contingencies = List.of(new Contingency("branch_S_SO_1", new BranchContingency("S_SO_1")));

        List<StateMonitor> monitors = createNetworkMonitors(network);

        List<Action> actions = List.of(new SwitchAction("openSwitch", "SS1_SS1_DJ_OMN", true),
                                       new LineConnectionAction("openLineSSO2", "S_SO_2", true, true),
                                       new PhaseTapChangerTapPositionAction("pstChangeTap", "NE_NO_1", false, 8));
        List<OperatorStrategy> operatorStrategies = List.of(new OperatorStrategy("strategy1", ContingencyContext.specificContingency("branch_S_SO_1"), new AllViolationCondition(List.of("S_SO_2")), List.of("openSwitch")),
                                                            new OperatorStrategy("strategy2", ContingencyContext.specificContingency("branch_S_SO_1"), new AllViolationCondition(List.of("S_SO_2")), List.of("openLineSSO2")),
                                                            new OperatorStrategy("strategy3", ContingencyContext.specificContingency("branch_S_SO_1"), new AllViolationCondition(List.of("S_SO_2")), List.of("pstChangeTap")));

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters,
                operatorStrategies, actions, Reporter.NO_OP);

        PreContingencyResult preContingencyResult = result.getPreContingencyResult();
        assertEquals(0, preContingencyResult.getLimitViolationsResult().getLimitViolations().size());

        PostContingencyResult postContingencyResult = getPostContingencyResult(result, "branch_S_SO_1");
        assertEquals(2, postContingencyResult.getLimitViolationsResult().getLimitViolations().size());

        for (LimitViolation limitViolation : postContingencyResult.getLimitViolationsResult().getLimitViolations()) {
            assertEquals("S_SO_2", limitViolation.getSubjectId());
            assertEquals(LimitViolationType.ACTIVE_POWER, limitViolation.getLimitType());
        }

        for (String operatorStrategyString : List.of("strategy1", "strategy2", "strategy3")) {
            OperatorStrategyResult operatorStrategyResult = getOperatorStrategyResult(result, operatorStrategyString);
            assertEquals(0, operatorStrategyResult.getLimitViolationsResult().getLimitViolations().size());
        }
    }

    @Test
    void testMetrixApparentPower() {
        GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory = new NaiveGraphConnectivityFactory<>(LfBus::getNum);
        securityAnalysisProvider = new OpenSecurityAnalysisProvider(matrixFactory, connectivityFactory);

        Network network = MetrixTutorialSixBusesSecurityAnalysisFactory.createWithApparentPowerLimits();

        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        LoadFlowParameters parameters = new LoadFlowParameters();
        parameters.setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD);
        parameters.setHvdcAcEmulation(false);
        securityAnalysisParameters.setLoadFlowParameters(parameters);

        List<Contingency> contingencies = List.of(new Contingency("branch_S_SO_1", new BranchContingency("S_SO_1")));

        List<StateMonitor> monitors = createNetworkMonitors(network);

        List<Action> actions = List.of(new SwitchAction("openSwitch", "SS1_SS1_DJ_OMN", true),
                                       new LineConnectionAction("openLineSSO2", "S_SO_2", true, true),
                                       new PhaseTapChangerTapPositionAction("pstChangeTap", "NE_NO_1", false, 8));
        List<OperatorStrategy> operatorStrategies = List.of(new OperatorStrategy("strategy1", ContingencyContext.specificContingency("branch_S_SO_1"), new AllViolationCondition(List.of("S_SO_2")), List.of("openSwitch")),
                                                            new OperatorStrategy("strategy2", ContingencyContext.specificContingency("branch_S_SO_1"), new AllViolationCondition(List.of("S_SO_2")), List.of("openLineSSO2")),
                                                            new OperatorStrategy("strategy3", ContingencyContext.specificContingency("branch_S_SO_1"), new AllViolationCondition(List.of("S_SO_2")), List.of("pstChangeTap")));

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters,
                operatorStrategies, actions, Reporter.NO_OP);

        PreContingencyResult preContingencyResult = result.getPreContingencyResult();
        assertEquals(0, preContingencyResult.getLimitViolationsResult().getLimitViolations().size());

        PostContingencyResult postContingencyResult = getPostContingencyResult(result, "branch_S_SO_1");
        assertEquals(2, postContingencyResult.getLimitViolationsResult().getLimitViolations().size());

        for (LimitViolation limitViolation : postContingencyResult.getLimitViolationsResult().getLimitViolations()) {
            assertEquals("S_SO_2", limitViolation.getSubjectId());
            assertEquals(LimitViolationType.APPARENT_POWER, limitViolation.getLimitType());
        }

        for (String operatorStrategyString : List.of("strategy1", "strategy2", "strategy3")) {
            OperatorStrategyResult operatorStrategyResult = getOperatorStrategyResult(result, operatorStrategyString);
            assertEquals(0, operatorStrategyResult.getLimitViolationsResult().getLimitViolations().size());
        }
    }

    @Test
    void testBranchOpenAtOneSideRecovery() {
        GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory = new NaiveGraphConnectivityFactory<>(LfBus::getNum);
        securityAnalysisProvider = new OpenSecurityAnalysisProvider(matrixFactory, connectivityFactory);
        var network = ConnectedComponentNetworkFactory.createTwoCcLinkedBySwitches();
        network.getLine("l46").getTerminal1().disconnect();
        network.getSwitch("s25").setOpen(true);
        network.getSwitch("s34").setOpen(true);
        List<Contingency> contingencies = List.of(new Contingency("line", new BranchContingency("l12")));
        List<Action> actions = List.of(new SwitchAction("closeSwitch", "s25", false));
        List<OperatorStrategy> operatorStrategies = List.of(new OperatorStrategy("strategy", ContingencyContext.specificContingency("line"), new TrueCondition(), List.of("closeSwitch")));
        List<StateMonitor> monitors = createAllBranchesMonitors(network);
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.getLoadFlowParameters().setDistributedSlack(false);
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters,
                operatorStrategies, actions, Reporter.NO_OP);
        assertEquals(-2.998, getOperatorStrategyResult(result, "strategy").getNetworkResult().getBranchResult("l23").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-2.990, getOperatorStrategyResult(result, "strategy").getNetworkResult().getBranchResult("l45").getP1(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testCheckActions() {
        Network network = MetrixTutorialSixBusesFactory.create();
        List<StateMonitor> monitors = createAllBranchesMonitors(network);
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        List<Contingency> contingencies = List.of(new Contingency("S_SO_1", new BranchContingency("S_SO_1")));

        List<Action> actions = List.of(new SwitchAction("openSwitch", "switch", true));
        List<OperatorStrategy> operatorStrategies = List.of(new OperatorStrategy("strategy", ContingencyContext.specificContingency("S_SO_1"), new AllViolationCondition(List.of("S_SO_2")), List.of("openSwitch")));
        CompletionException exception = assertThrows(CompletionException.class, () -> runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters,
                operatorStrategies, actions, Reporter.NO_OP));
        assertEquals("Switch 'switch' not found in the network", exception.getCause().getMessage());

        List<Action> actions2 = List.of(new LineConnectionAction("openLine", "line", true, true));
        List<OperatorStrategy> operatorStrategies2 = List.of(new OperatorStrategy("strategy2", ContingencyContext.specificContingency("S_SO_1"), new AllViolationCondition(List.of("S_SO_2")), List.of("openLine")));
        exception = assertThrows(CompletionException.class, () -> runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters,
                operatorStrategies2, actions2, Reporter.NO_OP));
        assertEquals("Branch 'line' not found in the network", exception.getCause().getMessage());

        List<Action> actions3 = List.of(new PhaseTapChangerTapPositionAction("pst", "pst1", false, 1));
        List<OperatorStrategy> operatorStrategies3 = List.of(new OperatorStrategy("strategy3", ContingencyContext.specificContingency("S_SO_1"), new TrueCondition(), List.of("pst")));
        exception = assertThrows(CompletionException.class, () -> runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters,
                operatorStrategies3, actions3, Reporter.NO_OP));
        assertEquals("Transformer 'pst1' not found in the network", exception.getCause().getMessage());

        List<Action> actions4 = List.of(new PhaseTapChangerTapPositionAction("pst", "pst2", false, 1, ThreeSides.ONE));
        List<OperatorStrategy> operatorStrategies4 = List.of(new OperatorStrategy("strategy4", ContingencyContext.specificContingency("S_SO_1"), new TrueCondition(), List.of("pst")));
        exception = assertThrows(CompletionException.class, () -> runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters,
                operatorStrategies4, actions4, Reporter.NO_OP));
        assertEquals("Transformer 'pst2' not found in the network", exception.getCause().getMessage());

        List<Action> actions5 = Collections.emptyList();
        List<OperatorStrategy> operatorStrategies5 = List.of(new OperatorStrategy("strategy5", ContingencyContext.specificContingency("S_SO_1"), new TrueCondition(), List.of("x")));
        exception = assertThrows(CompletionException.class, () -> runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters,
                operatorStrategies5, actions5, Reporter.NO_OP));
        assertEquals("Operator strategy 'strategy5' is associated to action 'x' but this action is not present in the list", exception.getCause().getMessage());

        List<Action> actions6 = List.of(new SwitchAction("openSwitch", "NOD1_NOD1  NE1  1_SC5_0", true));
        List<OperatorStrategy> operatorStrategies6 = List.of(new OperatorStrategy("strategy6", ContingencyContext.specificContingency("y"), new TrueCondition(), List.of("openSwitch")));
        exception = assertThrows(CompletionException.class, () -> runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters,
                operatorStrategies6, actions6, Reporter.NO_OP));
        assertEquals("Operator strategy 'strategy6' is associated to contingency 'y' but this contingency is not present in the list", exception.getCause().getMessage());
    }

    @Test
    void testDcSecurityAnalysisWithOperatorStrategy() {
        GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory = new NaiveGraphConnectivityFactory<>(LfBus::getNum);
        securityAnalysisProvider = new OpenSecurityAnalysisProvider(matrixFactory, connectivityFactory);

        Network network = NodeBreakerNetworkFactory.create3Bars();
        network.getSwitch("C1").setOpen(true);
        network.getSwitch("C2").setOpen(true);
        network.getLineStream().forEach(line -> {
            if (line.getCurrentLimits1().isPresent()) {
                line.getCurrentLimits1().orElseThrow().setPermanentLimit(310);
            }
            if (line.getCurrentLimits2().isPresent()) {
                line.getCurrentLimits2().orElseThrow().setPermanentLimit(310);
            }
        });

        List<Contingency> contingencies = Stream.of("L1", "L3", "L2")
                .map(id -> new Contingency(id, new BranchContingency(id)))
                .collect(Collectors.toList());

        List<Action> actions = List.of(new SwitchAction("action1", "C1", false),
                new SwitchAction("action3", "C2", false));

        List<OperatorStrategy> operatorStrategies = List.of(new OperatorStrategy("strategyL1", ContingencyContext.specificContingency("L1"), new TrueCondition(), List.of("action1")),
                new OperatorStrategy("strategyL3", ContingencyContext.specificContingency("L3"), new TrueCondition(), List.of("action3")),
                new OperatorStrategy("strategyL2", ContingencyContext.specificContingency("L2"), new TrueCondition(), List.of("action1", "action3")));

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        LoadFlowParameters parameters = new LoadFlowParameters();
        parameters.setDistributedSlack(false);
        parameters.setDc(true);
        setSlackBusId(parameters, "VL2_0");
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.setLoadFlowParameters(parameters);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters,
                operatorStrategies, actions, Reporter.NO_OP);
        assertEquals(400.0, result.getPreContingencyResult().getNetworkResult().getBranchResult("L1").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.0, result.getPreContingencyResult().getNetworkResult().getBranchResult("L2").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(200.0, result.getPreContingencyResult().getNetworkResult().getBranchResult("L3").getP1(), LoadFlowAssert.DELTA_POWER);

        //L1 Contingency then close C1
        assertEquals(0.0, getPostContingencyResult(result, "L1").getNetworkResult().getBranchResult("L2").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(200.0, getPostContingencyResult(result, "L1").getNetworkResult().getBranchResult("L3").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(400.0, getOperatorStrategyResult(result, "strategyL1").getNetworkResult().getBranchResult("L2").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(200.0, getOperatorStrategyResult(result, "strategyL1").getNetworkResult().getBranchResult("L3").getP1(), LoadFlowAssert.DELTA_POWER);

        //L3 Contingency then close C2
        assertEquals(0.0, getPostContingencyResult(result, "L3").getNetworkResult().getBranchResult("L2").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(400.0, getPostContingencyResult(result, "L3").getNetworkResult().getBranchResult("L1").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(200.0, getOperatorStrategyResult(result, "strategyL3").getNetworkResult().getBranchResult("L2").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(400.0, getOperatorStrategyResult(result, "strategyL3").getNetworkResult().getBranchResult("L1").getP1(), LoadFlowAssert.DELTA_POWER);

        //L2 Contingency then close C1 and C2
        assertEquals(400.0, getPostContingencyResult(result, "L2").getNetworkResult().getBranchResult("L1").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(200.0, getPostContingencyResult(result, "L2").getNetworkResult().getBranchResult("L3").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(300.0, getOperatorStrategyResult(result, "strategyL2").getNetworkResult().getBranchResult("L1").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(300.0, getOperatorStrategyResult(result, "strategyL2").getNetworkResult().getBranchResult("L3").getP1(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testSaDcLineConnectionAction() {
        Network network = FourBusNetworkFactory.create();
        List<Contingency> contingencies = Stream.of("l14")
                .map(id -> new Contingency(id, new BranchContingency(id)))
                .collect(Collectors.toList());

        List<Action> actions = List.of(new LineConnectionAction("openLine", "l13", true, true));
        List<OperatorStrategy> operatorStrategies = List.of(new OperatorStrategy("strategyL1", ContingencyContext.specificContingency("l14"), new TrueCondition(), List.of("openLine")));
        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        LoadFlowParameters parameters = new LoadFlowParameters();
        parameters.setDistributedSlack(false);
        parameters.setDc(true);
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.setLoadFlowParameters(parameters);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters,
                operatorStrategies, actions, Reporter.NO_OP);

        OperatorStrategyResult resultStratL1 = getOperatorStrategyResult(result, "strategyL1");
        BranchResult brl12 = resultStratL1.getNetworkResult().getBranchResult("l12");
        BranchResult brl23 = resultStratL1.getNetworkResult().getBranchResult("l23");
        BranchResult brl34 = resultStratL1.getNetworkResult().getBranchResult("l34");

        parameters.setDc(false);
        SecurityAnalysisParameters securityAnalysisParametersAc = new SecurityAnalysisParameters();
        securityAnalysisParametersAc.setLoadFlowParameters(parameters);
        SecurityAnalysisResult resultAc = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParametersAc,
                operatorStrategies, actions, Reporter.NO_OP);
        OperatorStrategyResult resultStratL1Ac = getOperatorStrategyResult(resultAc, "strategyL1");
        BranchResult brl12Ac = resultStratL1Ac.getNetworkResult().getBranchResult("l12");
        BranchResult brl23Ac = resultStratL1Ac.getNetworkResult().getBranchResult("l23");
        BranchResult brl34Ac = resultStratL1Ac.getNetworkResult().getBranchResult("l34");

        assertEquals(2.0, brl12.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(3.0, brl23.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-1.0, brl34.getP1(), LoadFlowAssert.DELTA_POWER);

        assertEquals(2.0, brl12Ac.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(3.0, brl23Ac.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-1.0, brl34Ac.getP1(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testSaDcPhaseTapChangerTapPositionAction() {
        Network network = MetrixTutorialSixBusesFactory.create();
        List<StateMonitor> monitors = createAllBranchesMonitors(network);
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        List<Contingency> contingencies = List.of(new Contingency("S_SO_1", new BranchContingency("S_SO_1")));
        List<Action> actions = List.of(new PhaseTapChangerTapPositionAction("pstAbsChange", "NE_NO_1", false, 1),
                new PhaseTapChangerTapPositionAction("pstRelChange", "NE_NO_1", true, -1));
        List<OperatorStrategy> operatorStrategies = List.of(new OperatorStrategy("strategyTapAbsChange", ContingencyContext.specificContingency("S_SO_1"), new TrueCondition(), List.of("pstAbsChange")),
                new OperatorStrategy("strategyTapRelChange", ContingencyContext.specificContingency("S_SO_1"), new TrueCondition(), List.of("pstRelChange")));

        LoadFlowParameters parameters = new LoadFlowParameters();
        parameters.setDistributedSlack(false);
        parameters.setDc(true);
        securityAnalysisParameters.setLoadFlowParameters(parameters);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters,
                operatorStrategies, actions, Reporter.NO_OP);

        assertNotNull(result);

        OperatorStrategyResult resultAbs = getOperatorStrategyResult(result, "strategyTapAbsChange");
        BranchResult brAbs = resultAbs.getNetworkResult().getBranchResult("S_SO_2");
        OperatorStrategyResult resultRel = getOperatorStrategyResult(result, "strategyTapRelChange");
        BranchResult brRel = resultRel.getNetworkResult().getBranchResult("S_SO_2");

        // Apply contingency by hand
        network.getLine("S_SO_1").getTerminal1().disconnect();
        network.getLine("S_SO_1").getTerminal2().disconnect();
        // Apply remedial action
        int originalTapPosition = network.getTwoWindingsTransformer("NE_NO_1").getPhaseTapChanger().getTapPosition();
        network.getTwoWindingsTransformer("NE_NO_1").getPhaseTapChanger().setTapPosition(1);
        loadFlowRunner.run(network, parameters);
        // Compare results
        assertEquals(network.getLine("S_SO_2").getTerminal1().getP(), brAbs.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getBranch("S_SO_2").getTerminal2().getP(), brAbs.getP2(), LoadFlowAssert.DELTA_POWER);

        // Check the second operator strategy: relative change
        network.getTwoWindingsTransformer("NE_NO_1").getPhaseTapChanger().setTapPosition(originalTapPosition - 1);
        loadFlowRunner.run(network, parameters);
        // Compare results
        assertEquals(network.getLine("S_SO_2").getTerminal1().getP(), brRel.getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getBranch("S_SO_2").getTerminal2().getP(), brRel.getP2(), LoadFlowAssert.DELTA_POWER);
    }

    private void testLoadAction(boolean dc) {
        GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory = new NaiveGraphConnectivityFactory<>(LfBus::getNum);
        securityAnalysisProvider = new OpenSecurityAnalysisProvider(matrixFactory, connectivityFactory);

        Network network = DistributedSlackNetworkFactory.createNetworkWithLoads();
        network.getLine("l24").newActivePowerLimits1().setPermanentLimit(150).add();
        double initialL4 = network.getLoad("l4").getP0();

        List<Contingency> contingencies = Stream.of("l2")
                .map(id -> new Contingency(id, new LoadContingency(id)))
                .collect(Collectors.toList());

        List<Action> actions = List.of(new LoadActionBuilder().withId("action1").withLoadId("l4").withRelativeValue(false).withActivePowerValue(90).build(),
                new LoadActionBuilder().withId("action2").withLoadId("l1").withRelativeValue(true).withActivePowerValue(50).build(),
                new LoadActionBuilder().withId("action3").withLoadId("l2").withRelativeValue(true).withActivePowerValue(10).build());

        List<OperatorStrategy> operatorStrategies = List.of(new OperatorStrategy("strategy1", ContingencyContext.specificContingency("l2"), new AnyViolationCondition(), List.of("action1", "action2")),
                new OperatorStrategy("strategy2", ContingencyContext.specificContingency("l2"), new AnyViolationCondition(), List.of("action3")));

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        LoadFlowParameters parameters = new LoadFlowParameters();
        parameters.setDc(dc);
        parameters.setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD);
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.setLoadFlowParameters(parameters);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters,
                operatorStrategies, actions, Reporter.NO_OP);

        network.getLoad("l2").getTerminal().disconnect();
        loadFlowRunner.run(network, parameters);

        PostContingencyResult postContingencyResult = getPostContingencyResult(result, "l2");
        assertEquals(network.getLine("l24").getTerminal1().getP(), postContingencyResult.getNetworkResult().getBranchResult("l24").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l14").getTerminal1().getP(), postContingencyResult.getNetworkResult().getBranchResult("l14").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l34").getTerminal1().getP(), postContingencyResult.getNetworkResult().getBranchResult("l34").getP1(), LoadFlowAssert.DELTA_POWER);

        network.getLoadStream().filter(load -> !load.getId().equals("l2")).forEach(load -> load.setP0(load.getTerminal().getP()));
        network.getLoad("l2").setP0(0);
        double postContingencyL1 = network.getLoad("l1").getP0();
        double postContingencyL2 = network.getLoad("l2").getP0();
        double postContingencyL4 = network.getLoad("l4").getP0();

        network.getLoad("l4").setP0(90 + postContingencyL4 - initialL4);
        network.getLoad("l1").setP0(postContingencyL1 + 50);
        loadFlowRunner.run(network, parameters);
        OperatorStrategyResult operatorStrategyResult = getOperatorStrategyResult(result, "strategy1");
        assertEquals(network.getLine("l24").getTerminal1().getP(), operatorStrategyResult.getNetworkResult().getBranchResult("l24").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l14").getTerminal1().getP(), operatorStrategyResult.getNetworkResult().getBranchResult("l14").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l34").getTerminal1().getP(), operatorStrategyResult.getNetworkResult().getBranchResult("l34").getP1(), LoadFlowAssert.DELTA_POWER);

        network.getLoad("l1").setP0(postContingencyL1);
        network.getLoad("l2").setP0(postContingencyL2); // because in contingency
        network.getLoad("l4").setP0(postContingencyL4);
        loadFlowRunner.run(network, parameters);
        OperatorStrategyResult operatorStrategyResult2 = getOperatorStrategyResult(result, "strategy2");
        assertEquals(network.getLine("l24").getTerminal1().getP(), operatorStrategyResult2.getNetworkResult().getBranchResult("l24").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l14").getTerminal1().getP(), operatorStrategyResult2.getNetworkResult().getBranchResult("l14").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l34").getTerminal1().getP(), operatorStrategyResult2.getNetworkResult().getBranchResult("l34").getP1(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testLoadAction() {
        testLoadAction(true);
        testLoadAction(false);
    }

    private void testGeneratorAction(boolean dc, LoadFlowParameters.BalanceType balanceType, double deltaG1, double deltaG2,
                                     double targetPG4, double slackBusPMaxMismatch) {
        GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory = new NaiveGraphConnectivityFactory<>(LfBus::getNum);
        securityAnalysisProvider = new OpenSecurityAnalysisProvider(matrixFactory, connectivityFactory);

        Network network = FourBusNetworkFactory.create();
        network.getLoad("d2").setP0(2.3); // to unbalance the network.

        final String lineInContingencyId = "l13";
        List<Contingency> contingencies = Stream.of(lineInContingencyId)
                .map(id -> new Contingency(id, new BranchContingency(id)))
                .collect(Collectors.toList());

        final String g1 = "g1";
        final String g2 = "g2";
        final String g4 = "g4";
        List<Action> actions = List.of(new GeneratorActionBuilder().withId("genAction_" + g1).withGeneratorId(g1).withActivePowerRelativeValue(true).withActivePowerValue(deltaG1).build(),
                                       new GeneratorActionBuilder().withId("genAction_" + g2).withGeneratorId(g2).withActivePowerRelativeValue(true).withActivePowerValue(deltaG2).build(),
                                       new GeneratorActionBuilder().withId("genAction_" + g4).withGeneratorId(g4).withActivePowerRelativeValue(false).withActivePowerValue(targetPG4).build());

        List<OperatorStrategy> operatorStrategies = List.of(new OperatorStrategy("strategyG1", ContingencyContext.specificContingency(lineInContingencyId), new TrueCondition(), List.of("genAction_" + g1)),
                                                            new OperatorStrategy("strategyG2", ContingencyContext.specificContingency(lineInContingencyId), new TrueCondition(), List.of("genAction_" + g2)),
                                                            new OperatorStrategy("strategyG3", ContingencyContext.specificContingency(lineInContingencyId), new TrueCondition(), List.of("genAction_" + g4)),
                                                            new OperatorStrategy("strategyG4", ContingencyContext.specificContingency(lineInContingencyId), new TrueCondition(), List.of("genAction_" + g1, "genAction_" + g2, "genAction_" + g4)));

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        LoadFlowParameters parameters = new LoadFlowParameters();
        parameters.setDistributedSlack(true).setBalanceType(balanceType);
        parameters.setDc(dc);
        OpenLoadFlowParameters openLoadFlowParameters = new OpenLoadFlowParameters();
        openLoadFlowParameters.setSlackBusPMaxMismatch(slackBusPMaxMismatch);
        parameters.addExtension(OpenLoadFlowParameters.class, openLoadFlowParameters);
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.setLoadFlowParameters(parameters);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters,
                operatorStrategies, actions, Reporter.NO_OP);

        // verify security analysis result through load flows step by step
        // apply contingency
        network.getLine(lineInContingencyId).getTerminal1().disconnect();
        network.getLine(lineInContingencyId).getTerminal2().disconnect();
        loadFlowRunner.run(network, parameters);
        assertEquals(network.getLine("l12").getTerminal1().getP(), getPostContingencyResult(result, lineInContingencyId).getNetworkResult().getBranchResult("l12").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l14").getTerminal1().getP(), getPostContingencyResult(result, lineInContingencyId).getNetworkResult().getBranchResult("l14").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l23").getTerminal1().getP(), getPostContingencyResult(result, lineInContingencyId).getNetworkResult().getBranchResult("l23").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l34").getTerminal1().getP(), getPostContingencyResult(result, lineInContingencyId).getNetworkResult().getBranchResult("l34").getP1(), LoadFlowAssert.DELTA_POWER);

        double g4InitialTargetP = network.getGenerator(g4).getTargetP();
        network.getGeneratorStream().forEach(gen -> gen.setTargetP(-gen.getTerminal().getP()));
        double g1PostContingencyTargetP = network.getGenerator(g1).getTargetP();
        double g2PostContingencyTargetP = network.getGenerator(g2).getTargetP();
        double g4PostContingencyTargetP = network.getGenerator(g4).getTargetP();

        // apply remedial action
        network.getGenerator(g1).setTargetP(g1PostContingencyTargetP + deltaG1);
        loadFlowRunner.run(network, parameters);
        assertEquals(network.getLine("l12").getTerminal1().getP(), getOperatorStrategyResult(result, "strategyG1").getNetworkResult().getBranchResult("l12").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l14").getTerminal1().getP(), getOperatorStrategyResult(result, "strategyG1").getNetworkResult().getBranchResult("l14").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l23").getTerminal1().getP(), getOperatorStrategyResult(result, "strategyG1").getNetworkResult().getBranchResult("l23").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l34").getTerminal1().getP(), getOperatorStrategyResult(result, "strategyG1").getNetworkResult().getBranchResult("l34").getP1(), LoadFlowAssert.DELTA_POWER);

        // reverse action and apply second remedial action
        network.getGenerator(g1).setTargetP(g1PostContingencyTargetP);
        network.getGenerator(g2).setTargetP(g2PostContingencyTargetP + deltaG2);
        loadFlowRunner.run(network, parameters);
        assertEquals(network.getLine("l12").getTerminal1().getP(), getOperatorStrategyResult(result, "strategyG2").getNetworkResult().getBranchResult("l12").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l14").getTerminal1().getP(), getOperatorStrategyResult(result, "strategyG2").getNetworkResult().getBranchResult("l14").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l23").getTerminal1().getP(), getOperatorStrategyResult(result, "strategyG2").getNetworkResult().getBranchResult("l23").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l34").getTerminal1().getP(), getOperatorStrategyResult(result, "strategyG2").getNetworkResult().getBranchResult("l34").getP1(), LoadFlowAssert.DELTA_POWER);

        // reverse action and apply third remedial action
        network.getGenerator(g2).setTargetP(g2PostContingencyTargetP);
        network.getGenerator(g4).setTargetP(targetPG4 + g4PostContingencyTargetP - g4InitialTargetP);
        loadFlowRunner.run(network, parameters);
        assertEquals(network.getLine("l12").getTerminal1().getP(), getOperatorStrategyResult(result, "strategyG3").getNetworkResult().getBranchResult("l12").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l14").getTerminal1().getP(), getOperatorStrategyResult(result, "strategyG3").getNetworkResult().getBranchResult("l14").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l23").getTerminal1().getP(), getOperatorStrategyResult(result, "strategyG3").getNetworkResult().getBranchResult("l23").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l34").getTerminal1().getP(), getOperatorStrategyResult(result, "strategyG3").getNetworkResult().getBranchResult("l34").getP1(), LoadFlowAssert.DELTA_POWER);

        // reverse action and apply fourth remedial action
        network.getGenerator(g2).setTargetP(g2PostContingencyTargetP + deltaG2);
        network.getGenerator(g1).setTargetP(g1PostContingencyTargetP + deltaG1);
        loadFlowRunner.run(network, parameters);
        assertEquals(network.getLine("l12").getTerminal1().getP(), getOperatorStrategyResult(result, "strategyG4").getNetworkResult().getBranchResult("l12").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l14").getTerminal1().getP(), getOperatorStrategyResult(result, "strategyG4").getNetworkResult().getBranchResult("l14").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l23").getTerminal1().getP(), getOperatorStrategyResult(result, "strategyG4").getNetworkResult().getBranchResult("l23").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l34").getTerminal1().getP(), getOperatorStrategyResult(result, "strategyG4").getNetworkResult().getBranchResult("l34").getP1(), LoadFlowAssert.DELTA_POWER);

    }

    @Test
    void testGeneratorAction() {
        testGeneratorAction(false, LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD, 2.0, -1.5, 2, 0.0001);
        testGeneratorAction(false, LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD, 1.0, -1.0, 4, 0.0001);
        testGeneratorAction(false, LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX, 1.77, -1.0, 0.0, 0.0005);
        testGeneratorAction(false, LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX, 1.0, -1.0, 2, 0.0005);
        testGeneratorAction(true, LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD, 2.0, -1.5, 0, 0.0001);
        testGeneratorAction(true, LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX, 1.0, -1.0, 2, 0.0001);
    }

    @Test
    void testActionOnGeneratorInContingency() {
        GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory = new NaiveGraphConnectivityFactory<>(LfBus::getNum);
        securityAnalysisProvider = new OpenSecurityAnalysisProvider(matrixFactory, connectivityFactory);

        Network network = DistributedSlackNetworkFactory.createNetworkWithLoads();
        network.getGenerator("g2").setTargetV(400).setVoltageRegulatorOn(true);

        List<Contingency> contingencies = Stream.of("g1")
                .map(id -> new Contingency(id, new GeneratorContingency(id)))
                .collect(Collectors.toList());

        List<Action> actions = List.of(new GeneratorActionBuilder().withId("action1").withGeneratorId("g1").withActivePowerRelativeValue(true).withActivePowerValue(100).build(),
                                       new GeneratorActionBuilder().withId("action2").withGeneratorId("g2").withActivePowerRelativeValue(false).withActivePowerValue(300).build());

        List<OperatorStrategy> operatorStrategies = List.of(new OperatorStrategy("strategy1", ContingencyContext.specificContingency("g1"), new TrueCondition(), List.of("action1")),
                                                            new OperatorStrategy("strategy2", ContingencyContext.specificContingency("g1"), new TrueCondition(), List.of("action2")));

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        LoadFlowParameters parameters = new LoadFlowParameters();
        parameters.setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD);
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.setLoadFlowParameters(parameters);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters,
                operatorStrategies, actions, Reporter.NO_OP);

        PostContingencyResult postContingencyResult = getPostContingencyResult(result, "g1");
        assertEquals(147.059, postContingencyResult.getNetworkResult().getBranchResult("l24").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-26.471, postContingencyResult.getNetworkResult().getBranchResult("l14").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-44.118, postContingencyResult.getNetworkResult().getBranchResult("l34").getP1(), LoadFlowAssert.DELTA_POWER);

        OperatorStrategyResult operatorStrategyResult = getOperatorStrategyResult(result, "strategy1");
        assertEquals(147.059, operatorStrategyResult.getNetworkResult().getBranchResult("l24").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-26.471, operatorStrategyResult.getNetworkResult().getBranchResult("l14").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-44.118, operatorStrategyResult.getNetworkResult().getBranchResult("l34").getP1(), LoadFlowAssert.DELTA_POWER);

        OperatorStrategyResult operatorStrategyResult2 = getOperatorStrategyResult(result, "strategy2");
        assertEquals(229.412, operatorStrategyResult2.getNetworkResult().getBranchResult("l24").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-35.294, operatorStrategyResult2.getNetworkResult().getBranchResult("l14").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-58.824, operatorStrategyResult2.getNetworkResult().getBranchResult("l34").getP1(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testNotFoundHvdcAction() {
        Network network = HvdcNetworkFactory.createWithHvdcInAcEmulation();
        List<Contingency> contingencies = new ArrayList<>();
        contingencies.add(Contingency.generator("g5"));
        List<StateMonitor> monitors = createAllBranchesMonitors(network);
        List<Action> actions = List.of(new HvdcActionBuilder().withId("action").withHvdcId("hvdc").withAcEmulationEnabled(false).build());
        List<OperatorStrategy> operatorStrategies = List.of(new OperatorStrategy("strategy", ContingencyContext.specificContingency("g5"), new TrueCondition(), List.of("action")));
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        CompletionException e = assertThrows(CompletionException.class, () -> runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters, operatorStrategies, actions, Reporter.NO_OP));
        assertEquals("Hvdc line 'hvdc' not found in the network", e.getCause().getMessage());
    }

    @Test
    void testHvdcAction() {
        Network network = HvdcNetworkFactory.createWithHvdcInAcEmulation();
        network.getHvdcLine("hvdc34").newExtension(HvdcAngleDroopActivePowerControlAdder.class)
                .withDroop(180)
                .withP0(0.f)
                .withEnabled(true)
                .add();

        List<Contingency> contingencies = new ArrayList<>();
        contingencies.add(Contingency.generator("g5"));

        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        List<Action> actions = List.of(new HvdcActionBuilder().withId("action1").withHvdcId("hvdc34").withAcEmulationEnabled(false).build(),
                new LoadActionBuilder().withId("action2").withLoadId("d2").withRelativeValue(true).withActivePowerValue(-2).build());

        List<OperatorStrategy> operatorStrategies = List.of(new OperatorStrategy("strategy1", ContingencyContext.specificContingency("g5"), new TrueCondition(), List.of("action1")),
                new OperatorStrategy("strategy2", ContingencyContext.specificContingency("g5"), new TrueCondition(), List.of("action2")));

        LoadFlowParameters parameters = new LoadFlowParameters();
        parameters.setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD);
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.setLoadFlowParameters(parameters);

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters, operatorStrategies, actions, Reporter.NO_OP);

        // compare with a loadflow.
        network.getGenerator("g5").getTerminal().disconnect();
        loadFlowRunner.run(network, parameters);
        network.getHvdcLine("hvdc34").setActivePowerSetpoint(Math.abs(Math.abs(network.getVscConverterStation("cs3").getTerminal().getP())));
        network.getHvdcLine("hvdc34").setConvertersMode(HvdcLine.ConvertersMode.SIDE_1_RECTIFIER_SIDE_2_INVERTER);
        parameters.setHvdcAcEmulation(false);
        loadFlowRunner.run(network, parameters);

        OperatorStrategyResult operatorStrategyResult1 = getOperatorStrategyResult(result, "strategy1");
        assertEquals(network.getLine("l13").getTerminal1().getP(), operatorStrategyResult1.getNetworkResult().getBranchResult("l13").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l12").getTerminal1().getP(), operatorStrategyResult1.getNetworkResult().getBranchResult("l12").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l23").getTerminal1().getP(), operatorStrategyResult1.getNetworkResult().getBranchResult("l23").getP1(), LoadFlowAssert.DELTA_POWER);

        parameters.setHvdcAcEmulation(true);
        network.getLoad("d2").setP0(network.getLoad("d2").getP0() - 2);
        loadFlowRunner.run(network, parameters);

        OperatorStrategyResult operatorStrategyResult2 = getOperatorStrategyResult(result, "strategy2");
        assertEquals(network.getLine("l13").getTerminal1().getP(), operatorStrategyResult2.getNetworkResult().getBranchResult("l13").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l12").getTerminal1().getP(), operatorStrategyResult2.getNetworkResult().getBranchResult("l12").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("l23").getTerminal1().getP(), operatorStrategyResult2.getNetworkResult().getBranchResult("l23").getP1(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testWithTieLineContingency() {
        Network network = BoundaryFactory.createWithTwoTieLines();
        List<Contingency> contingencies = List.of(new Contingency("contingency", List.of(new TieLineContingency("t12"))));
        List<StateMonitor> monitors = createNetworkMonitors(network);
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        List<Action> actions = List.of(new LineConnectionAction("openTieLine", "t12bis", true, true)); // just for testing, not relevant
        List<OperatorStrategy> operatorStrategies = List.of(new OperatorStrategy("strategy", ContingencyContext.specificContingency("contingency"), new TrueCondition(), List.of("openTieLine")));
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters, operatorStrategies, actions, Reporter.NO_OP);
        assertEquals(400.0, result.getOperatorStrategyResults().get(0).getNetworkResult().getBusResult("b3").getV(), LoadFlowAssert.DELTA_V);
        assertEquals(-0.0038, result.getOperatorStrategyResults().get(0).getNetworkResult().getBranchResult("l34").getQ2(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testShuntIncrementalOuterLoop() {
        Network network = VoltageControlNetworkFactory.createWithShuntSharedRemoteControl2();
        List<Contingency> contingencies = List.of(new Contingency("contingency", new LoadContingency("l4")));
        List<StateMonitor> monitors = createNetworkMonitors(network);
        LoadFlowParameters loadFlowParameters = new LoadFlowParameters();
        loadFlowParameters.setShuntCompensatorVoltageControlOn(true);
        OpenLoadFlowParameters openLoadFlowParameters = new OpenLoadFlowParameters();
        openLoadFlowParameters.setShuntVoltageControlMode(OpenLoadFlowParameters.ShuntVoltageControlMode.INCREMENTAL_VOLTAGE_CONTROL);
        loadFlowParameters.addExtension(OpenLoadFlowParameters.class, openLoadFlowParameters);
        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        securityAnalysisParameters.setLoadFlowParameters(loadFlowParameters);
        List<Action> actions = List.of(new SwitchAction("closeSwitch", "S", false));
        List<OperatorStrategy> operatorStrategies = List.of(new OperatorStrategy("strategy", ContingencyContext.specificContingency("contingency"), new TrueCondition(), List.of("closeSwitch")));
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters, operatorStrategies, actions, Reporter.NO_OP);
        PreContingencyResult preContingencyResult = result.getPreContingencyResult();
        assertEquals(400.0, preContingencyResult.getNetworkResult().getBusResult("b5").getV(), 0.001);
        assertEquals(385.313, preContingencyResult.getNetworkResult().getBusResult("b4").getV(), 0.001);
        OperatorStrategyResult operatorStrategyResult = getOperatorStrategyResult(result, "strategy");
        assertEquals(400.0, operatorStrategyResult.getNetworkResult().getBusResult("b5").getV(), 0.001);
        assertEquals(400.0, operatorStrategyResult.getNetworkResult().getBusResult("b4").getV(), 0.001);
    }

    @Test
    void testPhaseTapChangerActionThreeWindingsTransformer() {
        GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory = new NaiveGraphConnectivityFactory<>(LfBus::getNum);
        securityAnalysisProvider = new OpenSecurityAnalysisProvider(matrixFactory, connectivityFactory);

        Network network = PhaseControlFactory.createNetworkWithT3wt();

        network.newLine().setId("L3").setConnectableBus1("B2").setBus1("B2").setConnectableBus2("B4").setBus2("B4").setR(2).setX(100).add();
        network.getThreeWindingsTransformer("PS1").getLeg2().getPhaseTapChanger()
                .setRegulationMode(PhaseTapChanger.RegulationMode.valueOf("ACTIVE_POWER_CONTROL"))
                .setTargetDeadband(0)
                .setRegulating(true)
                .setTapPosition(0);

        SecurityAnalysisParameters securityAnalysisParameters = new SecurityAnalysisParameters();
        LoadFlowParameters parameters = new LoadFlowParameters();
        parameters.setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD);
        parameters.setHvdcAcEmulation(false);
        securityAnalysisParameters.setLoadFlowParameters(parameters);
        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        List<Contingency> contingencies = List.of(new Contingency("L1", new BranchContingency("L1")));
        List<Action> actions = List.of(new PhaseTapChangerTapPositionAction("pst", "PS1", false, 2, ThreeSides.TWO));
        List<OperatorStrategy> operatorStrategies = List.of(new OperatorStrategy("strategy1", ContingencyContext.specificContingency("L1"), new TrueCondition(), List.of("pst")));

        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters,
                operatorStrategies, actions, Reporter.NO_OP);
        assertEquals(80.00, result.getPreContingencyResult().getNetworkResult().getBranchResult("L1").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(132.75, getPostContingencyResult(result, "L1").getNetworkResult().getBranchResult("L2").getI1(), LoadFlowAssert.DELTA_I);
        assertEquals(68.2, getOperatorStrategyResult(result, "strategy1").getNetworkResult().getBranchResult("L2").getI1(), LoadFlowAssert.DELTA_I);

        loadFlowRunner.run(network, parameters);
        assertEquals(80.00, network.getLine("L1").getTerminal1().getI(), LoadFlowAssert.DELTA_I);

        network.getLine("L1").getTerminal1().disconnect();
        network.getLine("L1").getTerminal2().disconnect();
        loadFlowRunner.run(network, parameters);
        assertEquals(132.75, network.getLine("L2").getTerminal1().getI(), LoadFlowAssert.DELTA_I);

        network.getThreeWindingsTransformer("PS1").getLeg2().getPhaseTapChanger().setTapPosition(2);
        loadFlowRunner.run(network, parameters);
        assertEquals(68.2, network.getLine("L2").getTerminal1().getI(), LoadFlowAssert.DELTA_I);

        //test for exceptions
        List<Action> actions1 = List.of(new PhaseTapChangerTapPositionAction("pst_leg_1", "PS1", false, 2, ThreeSides.ONE));
        List<OperatorStrategy> operatorStrategies1 = List.of(new OperatorStrategy("strategy1", ContingencyContext.specificContingency("L1"),
                new TrueCondition(), List.of("pst_leg_1")));
        CompletionException exception = assertThrows(CompletionException.class, () -> runSecurityAnalysis(network, contingencies, monitors, securityAnalysisParameters,
                operatorStrategies1, actions1, Reporter.NO_OP));
        assertEquals("Tap position action: only one tap in branch PS1_leg_1", exception.getCause().getMessage());
    }

    @Test
    void testActionOnRetainedPtc() {
        Network network = PhaseControlFactory.createNetworkWithT2wt();
        network.newLine()
                .setId("L3")
                .setVoltageLevel1("VL1")
                .setBus1("B1")
                .setVoltageLevel2("VL2")
                .setBus2("B2")
                .setR(4.0)
                .setX(200.0)
                .add();

        List<Contingency> contingencies = List.of(new Contingency("CL3", new BranchContingency("L3")));
        List<StateMonitor> monitors = createAllBranchesMonitors(network);
        List<Action> actions = List.of(new PhaseTapChangerTapPositionAction("Aps1", "PS1", false, 2));
        List<OperatorStrategy> operatorStrategies = List.of(new OperatorStrategy("strategy1", ContingencyContext.specificContingency("CL3"), new TrueCondition(), List.of("Aps1")));
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, new SecurityAnalysisParameters(),
                operatorStrategies, actions, Reporter.NO_OP);

        network.getLine("L3").getTerminal1().disconnect();
        network.getLine("L3").getTerminal2().disconnect();
        network.getTwoWindingsTransformer("PS1").getPhaseTapChanger().setTapPosition(2);
        loadFlowRunner.run(network);

        assertEquals(network.getLine("L1").getTerminal1().getP(), getOperatorStrategyResult(result, "strategy1").getNetworkResult().getBranchResult("L1").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("L1").getTerminal2().getP(), getOperatorStrategyResult(result, "strategy1").getNetworkResult().getBranchResult("L1").getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("L2").getTerminal1().getP(), getOperatorStrategyResult(result, "strategy1").getNetworkResult().getBranchResult("L2").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("L2").getTerminal2().getP(), getOperatorStrategyResult(result, "strategy1").getNetworkResult().getBranchResult("L2").getP2(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testActionOnRetainedT3wtPtc() {
        Network network = PhaseControlFactory.createNetworkWithT3wt();
        network.newLine()
                .setId("L3")
                .setVoltageLevel1("VL1")
                .setBus1("B1")
                .setVoltageLevel2("VL2")
                .setBus2("B2")
                .setR(4.0)
                .setX(200.0)
                .add();

        List<Contingency> contingencies = List.of(new Contingency("CL3", new BranchContingency("L3")));
        List<StateMonitor> monitors = createAllBranchesMonitors(network);
        List<Action> actions = List.of(new PhaseTapChangerTapPositionAction("Aps1", "PS1", false, 2, ThreeSides.TWO));
        List<OperatorStrategy> operatorStrategies = List.of(new OperatorStrategy("strategy1", ContingencyContext.specificContingency("CL3"), new TrueCondition(), List.of("Aps1")));
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, new SecurityAnalysisParameters(),
                operatorStrategies, actions, Reporter.NO_OP);

        network.getLine("L3").getTerminal1().disconnect();
        network.getLine("L3").getTerminal2().disconnect();
        network.getThreeWindingsTransformer("PS1").getLeg2().getPhaseTapChanger().setTapPosition(2);
        loadFlowRunner.run(network);

        assertEquals(network.getLine("L1").getTerminal1().getP(), getOperatorStrategyResult(result, "strategy1").getNetworkResult().getBranchResult("L1").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("L1").getTerminal2().getP(), getOperatorStrategyResult(result, "strategy1").getNetworkResult().getBranchResult("L1").getP2(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("L2").getTerminal1().getP(), getOperatorStrategyResult(result, "strategy1").getNetworkResult().getBranchResult("L2").getP1(), LoadFlowAssert.DELTA_POWER);
        assertEquals(network.getLine("L2").getTerminal2().getP(), getOperatorStrategyResult(result, "strategy1").getNetworkResult().getBranchResult("L2").getP2(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testActionOnRetainedRtc() {
        Network network = VoltageControlNetworkFactory.createNetworkWithT2wt();
        List<Contingency> contingencies = List.of(new Contingency("contingency", new LoadContingency("LOAD_2")));
        List<StateMonitor> monitors = createNetworkMonitors(network);
        List<Action> actions = List.of(new RatioTapChangerTapPositionAction("action", "T2wT", false, 2));
        List<OperatorStrategy> operatorStrategies = List.of(new OperatorStrategy("strategy", ContingencyContext.specificContingency("contingency"), new TrueCondition(), List.of("action")));
        SecurityAnalysisResult result = runSecurityAnalysis(network, contingencies, monitors, new SecurityAnalysisParameters(),
                operatorStrategies, actions, Reporter.NO_OP);

        network.getLoad("LOAD_2").getTerminal().disconnect();
        network.getTwoWindingsTransformer("T2wT").getRatioTapChanger().setTapPosition(2);
        loadFlowRunner.run(network);

        assertEquals(network.getBusBreakerView().getBus("BUS_2").getV(),
                getOperatorStrategyResult(result, "strategy").getNetworkResult().getBusResult("BUS_2").getV(), LoadFlowAssert.DELTA_POWER);
    }
}
