/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac;

import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.openloadflow.NetworkCache;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowProvider;
import com.powsybl.openloadflow.network.EurostagFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static com.powsybl.openloadflow.util.LoadFlowAssert.assertVoltageEquals;
import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
class AcLoadFlowWithCachingTest {

    private LoadFlow.Runner loadFlowRunner;

    private LoadFlowParameters parameters;

    @BeforeEach
    void setUp() {
        loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        parameters = new LoadFlowParameters();
        OpenLoadFlowParameters.create(parameters)
                .setNetworkCacheEnabled(true);
        NetworkCache.INSTANCE.clear();
    }

    @Test
    void testTargetV() {
        var network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        var gen = network.getGenerator("GEN");
        var ngen = network.getBusBreakerView().getBus("NGEN");
        var nload = network.getBusBreakerView().getBus("NLOAD");

        assertEquals(0, NetworkCache.INSTANCE.getEntryCount());
        var result = loadFlowRunner.run(network, parameters);
        assertEquals(1, NetworkCache.INSTANCE.getEntryCount());
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        assertEquals(4, result.getComponentResults().get(0).getIterationCount());
        assertVoltageEquals(24.5, ngen);
        assertVoltageEquals(147.578, nload);

        gen.setTargetV(24.1);

        result = loadFlowRunner.run(network, parameters);
        assertEquals(1, NetworkCache.INSTANCE.getEntryCount());
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        assertEquals(2, result.getComponentResults().get(0).getIterationCount());
        assertVoltageEquals(24.1, ngen);
        assertVoltageEquals(144.402, nload);

        result = loadFlowRunner.run(network, parameters);
        assertEquals(1, NetworkCache.INSTANCE.getEntryCount());
        // FIXME NO_CALCULATION should be added to API
        assertEquals(LoadFlowResult.ComponentResult.Status.FAILED, result.getComponentResults().get(0).getStatus());
        assertEquals(0, result.getComponentResults().get(0).getIterationCount());
    }

    @Test
    void testParameterChange() {
        var network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());

        assertEquals(0, NetworkCache.INSTANCE.getEntryCount());
        loadFlowRunner.run(network, parameters);
        assertEquals(1, NetworkCache.INSTANCE.getEntryCount());
        NetworkCache.Entry entry = NetworkCache.INSTANCE.findEntry(network).orElseThrow();
        loadFlowRunner.run(network, parameters);
        assertEquals(1, NetworkCache.INSTANCE.getEntryCount());
        NetworkCache.Entry entry2 = NetworkCache.INSTANCE.findEntry(network).orElseThrow();
        assertSame(entry, entry2); // reuse same cache

        // run with different parameters
        parameters.setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD);
        loadFlowRunner.run(network, parameters);
        assertEquals(1, NetworkCache.INSTANCE.getEntryCount());
        NetworkCache.Entry entry3 = NetworkCache.INSTANCE.findEntry(network).orElseThrow();
        assertNotSame(entry, entry3); // cache has been evicted and recreated
    }

    @Test
    @Disabled("Disabled by default because not reliable, depends on JVM, garbage collector, and machine performance")
    void testCacheEviction() {
        int runCount = 10;
        for (int i = 0; i < runCount; i++) {
            var network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
            loadFlowRunner.run(network, parameters);
            System.gc();
        }
        assertTrue(NetworkCache.INSTANCE.getEntryCount() < runCount);
    }

    @Test
    void testUnsupportedAttributeChange() {
        var network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        var gen = network.getGenerator("GEN");

        loadFlowRunner.run(network, parameters);
        assertNotNull(NetworkCache.INSTANCE.findEntry(network).orElseThrow().getContexts());
        gen.setTargetQ(10);
        assertNull(NetworkCache.INSTANCE.findEntry(network).orElseThrow().getContexts());
    }

    @Test
    void testPropertiesChange() {
        var network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        var gen = network.getGenerator("GEN");

        loadFlowRunner.run(network, parameters);
        assertNotNull(NetworkCache.INSTANCE.findEntry(network).orElseThrow().getContexts());
        gen.setProperty("foo", "bar");
        assertNotNull(NetworkCache.INSTANCE.findEntry(network).orElseThrow().getContexts());
        gen.setProperty("foo", "baz");
        assertNotNull(NetworkCache.INSTANCE.findEntry(network).orElseThrow().getContexts());
        gen.removeProperty("foo");
        assertNotNull(NetworkCache.INSTANCE.findEntry(network).orElseThrow().getContexts());
    }

    @Test
    void testVariantChange() {
        var network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());

        loadFlowRunner.run(network, parameters);
        assertNotNull(NetworkCache.INSTANCE.findEntry(network).orElseThrow().getContexts());

        network.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "v");
        assertNull(NetworkCache.INSTANCE.findEntry(network).orElseThrow().getContexts());

        loadFlowRunner.run(network, parameters);
        assertNotNull(NetworkCache.INSTANCE.findEntry(network).orElseThrow().getContexts());

        network.getVariantManager().cloneVariant(VariantManagerConstants.INITIAL_VARIANT_ID, "v", true);
        assertNull(NetworkCache.INSTANCE.findEntry(network).orElseThrow().getContexts());

        loadFlowRunner.run(network, parameters);
        assertNotNull(NetworkCache.INSTANCE.findEntry(network).orElseThrow().getContexts());

        network.getVariantManager().removeVariant("v");
        assertNull(NetworkCache.INSTANCE.findEntry(network).orElseThrow().getContexts());
    }

    @Test
    void testLoadAddition() {
        var network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());

        loadFlowRunner.run(network, parameters);
        assertNotNull(NetworkCache.INSTANCE.findEntry(network).orElseThrow().getContexts());

        network.getVoltageLevel("VLLOAD").newLoad()
                .setId("NEWLOAD")
                .setConnectableBus("NLOAD")
                .setBus("NLOAD")
                .setP0(10)
                .setQ0(10)
                .add();

        assertNull(NetworkCache.INSTANCE.findEntry(network).orElseThrow().getContexts());
    }

    @Test
    void testLoadRemoval() {
        var network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());

        loadFlowRunner.run(network, parameters);
        assertNotNull(NetworkCache.INSTANCE.findEntry(network).orElseThrow().getContexts());

        network.getLoad("LOAD").remove();

        assertNull(NetworkCache.INSTANCE.findEntry(network).orElseThrow().getContexts());
    }
}