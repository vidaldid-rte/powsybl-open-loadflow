/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.network.impl;

import com.google.auto.service.AutoService;
import com.google.common.base.Stopwatch;
import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.*;
import com.powsybl.openloadflow.network.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.powsybl.openloadflow.util.Markers.PERFORMANCE_MARKER;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
@AutoService(LfNetworkLoader.class)
public class LfNetworkLoaderImpl implements LfNetworkLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(LfNetworkLoaderImpl.class);

    private static class CreationContext {

        private final Set<Branch> branchSet = new LinkedHashSet<>();

        private final List<DanglingLine> danglingLines = new ArrayList<>();

        private final Set<ThreeWindingsTransformer> t3wtSet = new LinkedHashSet<>();

        private final Map<String, Integer> busIdToNum = new HashMap<>();
    }

    private static List<LfBus> createBuses(List<Bus> buses, boolean generatorVoltageRemoteControl, CreationContext creationContext) {
        List<LfBus> lfBuses = new ArrayList<>(buses.size());
        int[] generatorCount = new int[1];
        Map<LfBusImpl, String> generatorRemoteControlTargetBusId = new HashMap<>();

        for (Bus bus : buses) {
            LfBusImpl lfBus = addLfBus(bus, lfBuses, creationContext.busIdToNum);

            bus.visitConnectedEquipments(new DefaultTopologyVisitor() {

                private void visitBranch(Branch branch) {
                    creationContext.branchSet.add(branch);
                }

                @Override
                public void visitLine(Line line, Branch.Side side) {
                    visitBranch(line);
                }

                @Override
                public void visitTwoWindingsTransformer(TwoWindingsTransformer transformer, Branch.Side side) {
                    visitBranch(transformer);
                }

                @Override
                public void visitThreeWindingsTransformer(ThreeWindingsTransformer transformer, ThreeWindingsTransformer.Side side) {
                    creationContext.t3wtSet.add(transformer);
                }

                @Override
                public void visitGenerator(Generator generator) {
                    double scaleV = 1;
                    String controlBusId = generator.getRegulatingTerminal().getBusView().getBus().getId();
                    String connectedBusId = generator.getTerminal().getBusView().getBus().getId();
                    if (!Objects.equals(controlBusId, connectedBusId)) {
                        if (generatorVoltageRemoteControl) {
                            // remote control target bus will be set later because target bus might not have
                            // been yet created
                            generatorRemoteControlTargetBusId.put(lfBus, controlBusId);
                        } else {
                            double previousTargetV = generator.getTargetV();
                            double remoteNominalV = generator.getRegulatingTerminal().getVoltageLevel().getNominalV();
                            double localNominalV = generator.getTerminal().getVoltageLevel().getNominalV();
                            scaleV = localNominalV / remoteNominalV;
                            LOGGER.warn("Generator remote voltage control is not yet supported. The voltage target of generator "
                                    + generator.getId() + " with remote control is rescaled from " + previousTargetV + " to "
                                    + previousTargetV * scaleV);
                        }
                    }
                    lfBus.addGenerator(generator, scaleV);
                    generatorCount[0]++;
                }

                @Override
                public void visitLoad(Load load) {
                    lfBus.addLoad(load);
                }

                @Override
                public void visitShuntCompensator(ShuntCompensator sc) {
                    lfBus.addShuntCompensator(sc);
                }

                @Override
                public void visitDanglingLine(DanglingLine danglingLine) {
                    creationContext.danglingLines.add(danglingLine);
                }

                @Override
                public void visitStaticVarCompensator(StaticVarCompensator staticVarCompensator) {
                    lfBus.addStaticVarCompensator(staticVarCompensator);
                }

                @Override
                public void visitBattery(Battery battery) {
                    lfBus.addBattery(battery);
                }

                @Override
                public void visitHvdcConverterStation(HvdcConverterStation<?> converterStation) {
                    switch (converterStation.getHvdcType()) {
                        case VSC:
                            lfBus.addVscConverterStation((VscConverterStation) converterStation);
                            break;
                        case LCC:
                            throw new UnsupportedOperationException("LCC converter station is not yet supported");
                        default:
                            throw new IllegalStateException("Unknown HVDC converter station type: " + converterStation.getHvdcType());
                    }
                }
            });
        }

        if (generatorCount[0] == 0) {
            throw new PowsyblException("Connected component without any regulating generator");
        }

        // set generators remote control target bus
        for (Map.Entry<LfBusImpl, String> e : generatorRemoteControlTargetBusId.entrySet()) {
            LfBusImpl generatorBus = e.getKey();
            String remoteControlTargetBusId = e.getValue();
            LfBus remoteControlTargetBus = lfBuses.get(creationContext.busIdToNum.get(remoteControlTargetBusId));
            generatorBus.setRemoteControlTargetBus((LfBusImpl) remoteControlTargetBus);
        }

        return lfBuses;
    }

    private static List<LfBranch> createBranches(List<LfBus> lfBuses, CreationContext creationContext) {
        List<LfBranch> lfBranches = new ArrayList<>();

        for (Branch branch : creationContext.branchSet) {
            LfBus lfBus1 = getLfBus(branch.getTerminal1(), lfBuses, creationContext.busIdToNum);
            LfBus lfBus2 = getLfBus(branch.getTerminal2(), lfBuses, creationContext.busIdToNum);
            lfBranches.add(LfBranchImpl.create(branch, lfBus1, lfBus2));
        }

        for (DanglingLine danglingLine : creationContext.danglingLines) {
            LfDanglingLineBus lfBus2 = addLfBus(danglingLine, lfBuses, creationContext.busIdToNum);
            LfBus lfBus1 = getLfBus(danglingLine.getTerminal(), lfBuses, creationContext.busIdToNum);
            lfBranches.add(LfDanglingLineBranch.create(danglingLine, lfBus1, lfBus2));
        }

        for (ThreeWindingsTransformer t3wt : creationContext.t3wtSet) {
            LfStarBus lfBus0 = addLfBus(t3wt, lfBuses, creationContext.busIdToNum);
            LfBus lfBus1 = getLfBus(t3wt.getLeg1().getTerminal(), lfBuses, creationContext.busIdToNum);
            LfBus lfBus2 = getLfBus(t3wt.getLeg2().getTerminal(), lfBuses, creationContext.busIdToNum);
            LfBus lfBus3 = getLfBus(t3wt.getLeg3().getTerminal(), lfBuses, creationContext.busIdToNum);
            lfBranches.add(LfLegBranch.create(lfBus1, lfBus0, t3wt, t3wt.getLeg1()));
            lfBranches.add(LfLegBranch.create(lfBus2, lfBus0, t3wt, t3wt.getLeg2()));
            lfBranches.add(LfLegBranch.create(lfBus3, lfBus0, t3wt, t3wt.getLeg3()));
        }

        // create bus -> branches link
        for (LfBranch branch : lfBranches) {
            if (branch.getBus1() != null) {
                branch.getBus1().addBranch(branch);
            }
            if (branch.getBus2() != null) {
                branch.getBus2().addBranch(branch);
            }
        }

        return lfBranches;
    }

    private static LfBus getLfBus(Terminal terminal, List<LfBus> lfBuses, Map<String, Integer> busIdToNum) {
        Bus bus = terminal.getBusView().getBus();
        if (bus != null) {
            int num = busIdToNum.get(bus.getId());
            return lfBuses.get(num);
        }
        return null;
    }

    private static LfBusImpl addLfBus(Bus bus, List<LfBus> lfBuses, Map<String, Integer> busIdToNum) {
        int busNum = lfBuses.size();
        LfBusImpl lfBus = LfBusImpl.create(bus, busNum);
        busIdToNum.put(bus.getId(), busNum);
        lfBuses.add(lfBus);
        return lfBus;
    }

    private static LfDanglingLineBus addLfBus(DanglingLine danglingLine, List<LfBus> lfBuses, Map<String, Integer> busIdToNum) {
        int busNum = lfBuses.size();
        LfDanglingLineBus lfBus = new LfDanglingLineBus(danglingLine, busNum);
        busIdToNum.put(lfBus.getId(), busNum);
        lfBuses.add(lfBus);
        return lfBus;
    }

    private static LfStarBus addLfBus(ThreeWindingsTransformer t3wt, List<LfBus> lfBuses, Map<String, Integer> busIdToNum) {
        int busNum = lfBuses.size();
        LfStarBus lfBus = new LfStarBus(t3wt, busNum);
        busIdToNum.put(lfBus.getId(), busNum);
        lfBuses.add(lfBus);
        return lfBus;
    }

    private static LfNetwork create(List<Bus> buses, SlackBusSelector slackBusSelector, boolean generatorVoltageRemoteControl) {
        CreationContext creationContext = new CreationContext();
        List<LfBus> lfBuses = createBuses(buses, generatorVoltageRemoteControl, creationContext);
        List<LfBranch> lfBranches = createBranches(lfBuses, creationContext);
        return new LfNetwork(lfBuses, lfBranches, slackBusSelector);
    }

    @Override
    public Optional<List<LfNetwork>> load(Object network, SlackBusSelector slackBusSelector, boolean generatorVoltageRemoteControl) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(slackBusSelector);

        if (network instanceof Network) {
            Stopwatch stopwatch = Stopwatch.createStarted();

            Map<Integer, List<Bus>> buseByCc = new TreeMap<>();
            for (Bus bus : ((Network) network).getBusView().getBuses()) {
                Component cc = bus.getConnectedComponent();
                if (cc != null) {
                    buseByCc.computeIfAbsent(cc.getNum(), k -> new ArrayList<>()).add(bus);
                }
            }

            List<LfNetwork> lfNetworks = buseByCc.entrySet().stream()
                    .filter(e -> e.getKey() == ComponentConstants.MAIN_NUM)
                    .map(e -> create(e.getValue(), slackBusSelector, generatorVoltageRemoteControl))
                    .collect(Collectors.toList());

            stopwatch.stop();
            LOGGER.debug(PERFORMANCE_MARKER, "LF networks created in {} ms", stopwatch.elapsed(TimeUnit.MILLISECONDS));

            return Optional.of(lfNetworks);
        }

        return Optional.empty();
    }
}
