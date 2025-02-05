/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac.outerloop;

import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.reporter.Reporter;
import com.powsybl.openloadflow.ac.AcOuterLoopContext;
import com.powsybl.openloadflow.lf.outerloop.DistributedSlackContextData;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopStatus;
import com.powsybl.openloadflow.network.util.ActivePowerDistribution;
import com.powsybl.openloadflow.util.PerUnit;
import com.powsybl.openloadflow.util.Reports;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class DistributedSlackOuterLoop implements AcOuterLoop {

    private static final Logger LOGGER = LoggerFactory.getLogger(DistributedSlackOuterLoop.class);

    public static final String NAME = "DistributedSlack";

    private final double slackBusPMaxMismatch;

    private final ActivePowerDistribution activePowerDistribution;

    public DistributedSlackOuterLoop(ActivePowerDistribution activePowerDistribution, double slackBusPMaxMismatch) {
        this.activePowerDistribution = Objects.requireNonNull(activePowerDistribution);
        this.slackBusPMaxMismatch = slackBusPMaxMismatch;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void initialize(AcOuterLoopContext context) {
        var contextData = new DistributedSlackContextData();
        context.setData(contextData);
    }

    @Override
    public OuterLoopStatus check(AcOuterLoopContext context, Reporter reporter) {
        double slackBusActivePowerMismatch = context.getLastSolverResult().getSlackBusActivePowerMismatch();
        if (Math.abs(slackBusActivePowerMismatch) > slackBusPMaxMismatch / PerUnit.SB) {
            ActivePowerDistribution.Result result = activePowerDistribution.run(context.getNetwork(), slackBusActivePowerMismatch);
            double remainingMismatch = result.remainingMismatch();
            double distributedActivePower = slackBusActivePowerMismatch - remainingMismatch;
            DistributedSlackContextData contextData = (DistributedSlackContextData) context.getData();
            contextData.addDistributedActivePower(distributedActivePower);
            if (Math.abs(remainingMismatch) > ActivePowerDistribution.P_RESIDUE_EPS) {
                Reports.reportMismatchDistributionFailure(reporter, context.getIteration(), remainingMismatch * PerUnit.SB);

                switch (context.getLoadFlowContext().getParameters().getSlackDistributionFailureBehavior()) {
                    case THROW ->
                        throw new PowsyblException("Failed to distribute slack bus active power mismatch, "
                                + remainingMismatch * PerUnit.SB + " MW remains");
                    case LEAVE_ON_SLACK_BUS -> {
                        LOGGER.warn("Failed to distribute slack bus active power mismatch, {} MW remains",
                                remainingMismatch * PerUnit.SB);
                        return result.movedBuses() ? OuterLoopStatus.UNSTABLE : OuterLoopStatus.STABLE;
                    }
                    case FAIL -> {
                        LOGGER.error("Failed to distribute slack bus active power mismatch, {} MW remains",
                                remainingMismatch * PerUnit.SB);
                        // Mismatches reported in LoadFlowResult on slack bus(es) are the mismatches of the last NR run.
                        // Since we will not be re-running an NR, revert distributedActivePower reporting which would otherwise be misleading.
                        // Said differently, we report that we didn't distribute anything, and this is indeed consistent with the network state.
                        contextData.addDistributedActivePower(-distributedActivePower);
                        return OuterLoopStatus.FAILED;
                    }
                }
            } else {
                Reports.reportMismatchDistributionSuccess(reporter, context.getIteration(), slackBusActivePowerMismatch * PerUnit.SB, result.iteration());

                LOGGER.info("Slack bus active power ({} MW) distributed in {} iterations",
                        slackBusActivePowerMismatch * PerUnit.SB, result.iteration());

                return OuterLoopStatus.UNSTABLE;
            }
        }

        Reports.reportNoMismatchDistribution(reporter, context.getIteration());

        LOGGER.debug("Already balanced");

        return OuterLoopStatus.STABLE;
    }
}
