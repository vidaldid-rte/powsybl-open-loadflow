/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow;

import com.powsybl.commons.reporter.Reporter;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Switch;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.openloadflow.ac.AcLoadFlowContext;
import com.powsybl.openloadflow.ac.AcLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcLoadFlowResult;
import com.powsybl.openloadflow.ac.AcloadFlowEngine;
import com.powsybl.openloadflow.ac.solver.AcSolverStatus;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopStatus;
import com.powsybl.openloadflow.network.impl.LfNetworkList;
import com.powsybl.openloadflow.network.LfTopoConfig;
import com.powsybl.openloadflow.network.impl.Networks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class AcLoadFlowFromCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(AcLoadFlowFromCache.class);

    private final Network network;

    private final LoadFlowParameters parameters;

    private final OpenLoadFlowParameters parametersExt;

    private final AcLoadFlowParameters acParameters;

    private final Reporter reporter;

    public AcLoadFlowFromCache(Network network, LoadFlowParameters parameters, OpenLoadFlowParameters parametersExt,
                               AcLoadFlowParameters acParameters, Reporter reporter) {
        this.network = Objects.requireNonNull(network);
        this.parameters = Objects.requireNonNull(parameters);
        this.parametersExt = Objects.requireNonNull(parametersExt);
        this.acParameters = Objects.requireNonNull(acParameters);
        this.reporter = Objects.requireNonNull(reporter);
    }

    private void configureSwitches(LfTopoConfig topoConfig) {
        for (String switchId : parametersExt.getActionableSwitchesIds()) {
            Switch sw = network.getSwitch(switchId);
            if (sw != null) {
                if (sw.isOpen()) {
                    topoConfig.getSwitchesToClose().add(sw);
                } else {
                    topoConfig.getSwitchesToOpen().add(sw);
                }
            } else {
                LOGGER.warn("Actionable switch '{}' does not exist", switchId);
            }
        }
        if (topoConfig.isBreaker()) {
            acParameters.getNetworkParameters().setBreakers(true);
        }
    }

    private List<AcLoadFlowContext> initContexts(NetworkCache.Entry entry) {
        List<AcLoadFlowContext> contexts;
        LfTopoConfig topoConfig = new LfTopoConfig();
        configureSwitches(topoConfig);

        // Because of caching, we only need to switch back to working variant but not to remove the variant, thus
        // WorkingVariantReverter is used instead of DefaultVariantCleaner
        try (LfNetworkList lfNetworkList = Networks.load(network, acParameters.getNetworkParameters(), topoConfig,
                LfNetworkList.WorkingVariantReverter::new, reporter)) {
            contexts = lfNetworkList.getList()
                    .stream()
                    .map(n -> new AcLoadFlowContext(n, acParameters))
                    .collect(Collectors.toList());
            entry.setContexts(contexts);
            LfNetworkList.VariantCleaner variantCleaner = lfNetworkList.getVariantCleaner();
            if (variantCleaner != null) {
                entry.setTmpVariantId(variantCleaner.getTmpVariantId());
            }
        }
        return contexts;
    }

    private static AcLoadFlowResult run(AcLoadFlowContext context) {
        if (!context.getNetwork().isValid()) {
            return AcLoadFlowResult.createNoCalculationResult(context.getNetwork());
        }
        if (context.isNetworkUpdated()) {
            AcLoadFlowResult result = new AcloadFlowEngine(context)
                    .run();
            context.setNetworkUpdated(false);
            return result;
        }
        return new AcLoadFlowResult(context.getNetwork(), 0, 0, AcSolverStatus.CONVERGED, OuterLoopStatus.STABLE, 0d, 0d);
    }

    public List<AcLoadFlowResult> run() {
        NetworkCache.Entry entry = NetworkCache.INSTANCE.get(network, parameters);
        List<AcLoadFlowContext> contexts = entry.getContexts();
        if (contexts == null) {
            contexts = initContexts(entry);
        }
        return contexts.stream()
                .map(AcLoadFlowFromCache::run)
                .collect(Collectors.toList());
    }
}
