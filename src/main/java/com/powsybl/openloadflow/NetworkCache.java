/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow;

import com.powsybl.iidm.network.*;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.openloadflow.ac.AcLoadFlowContext;
import com.powsybl.openloadflow.ac.AcLoadFlowResult;
import com.powsybl.openloadflow.ac.solver.AcSolverStatus;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.network.impl.AbstractLfGenerator;
import com.powsybl.openloadflow.network.util.PreviousValueVoltageInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiPredicate;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public enum NetworkCache {
    INSTANCE;

    private static final Logger LOGGER = LoggerFactory.getLogger(NetworkCache.class);

    public static class Entry extends DefaultNetworkListener {

        private final WeakReference<Network> networkRef;

        private final String workingVariantId;
        private String tmpVariantId;

        private final LoadFlowParameters parameters;

        private List<AcLoadFlowContext> contexts;

        private boolean pause = false;

        public Entry(Network network, LoadFlowParameters parameters) {
            Objects.requireNonNull(network);
            this.networkRef = new WeakReference<>(network);
            this.workingVariantId = network.getVariantManager().getWorkingVariantId();
            this.parameters = Objects.requireNonNull(parameters);
        }

        public WeakReference<Network> getNetworkRef() {
            return networkRef;
        }

        public String getWorkingVariantId() {
            return workingVariantId;
        }

        public void setTmpVariantId(String tmpVariantId) {
            this.tmpVariantId = tmpVariantId;
        }

        public List<AcLoadFlowContext> getContexts() {
            return contexts;
        }

        public void setContexts(List<AcLoadFlowContext> contexts) {
            this.contexts = contexts;
        }

        public LoadFlowParameters getParameters() {
            return parameters;
        }

        public void setPause(boolean pause) {
            this.pause = pause;
        }

        private void reset() {
            if (contexts != null) {
                for (AcLoadFlowContext context : contexts) {
                    context.close();
                }
                contexts = null;
            }
        }

        private void onStructureChange() {
            // too difficult to update LfNetwork incrementally
            reset();
        }

        @Override
        public void onCreation(Identifiable identifiable) {
            onStructureChange();
        }

        @Override
        public void afterRemoval(String s) {
            onStructureChange();
        }

        private static Optional<Bus> getBus(Injection<?> injection, AcLoadFlowContext context) {
            return Optional.ofNullable(context.getParameters().getNetworkParameters().isBreakers()
                    ? injection.getTerminal().getBusBreakerView().getBus()
                    : injection.getTerminal().getBusView().getBus());
        }

        private static Optional<LfBus> getLfBus(Injection<?> injection, AcLoadFlowContext context) {
            return getBus(injection, context)
                    .map(bus -> context.getNetwork().getBusById(bus.getId()));
        }

        private boolean onInjectionUpdate(Injection<?> injection, String attribute, BiPredicate<AcLoadFlowContext, LfBus> handler) {
            boolean found = false;
            for (AcLoadFlowContext context : contexts) {
                found |= getLfBus(injection, context)
                        .map(lfBus -> {
                            boolean done = handler.test(context, lfBus);
                            if (done) {
                                context.setNetworkUpdated(true);
                            }
                            return done;
                        })
                        .orElse(Boolean.FALSE);
            }
            if (!found) {
                LOGGER.warn("Cannot update attribute {} of injection '{}'", attribute, injection.getId());
            }
            return found;
        }

        private boolean onGeneratorUpdate(Generator generator, String attribute, Object oldValue, Object newValue) {
            return onInjectionUpdate(generator, attribute, (context, lfBus) -> {
                if (attribute.equals("targetV")) {
                    double valueShift = (double) newValue - (double) oldValue;
                    GeneratorVoltageControl voltageControl = lfBus.getGeneratorVoltageControl().orElseThrow();
                    double nominalV = voltageControl.getControlledBus().getNominalV();
                    double newTargetV = voltageControl.getTargetValue() + valueShift / nominalV;
                    LfNetworkParameters networkParameters = context.getParameters().getNetworkParameters();
                    if (AbstractLfGenerator.checkTargetV(generator.getId(), newTargetV, nominalV, networkParameters, null)) {
                        voltageControl.setTargetValue(newTargetV);
                    } else {
                        context.getNetwork().getGeneratorById(generator.getId()).setGeneratorControlType(LfGenerator.GeneratorControlType.OFF);
                        if (lfBus.getGenerators().stream().noneMatch(gen -> gen.getGeneratorControlType() == LfGenerator.GeneratorControlType.VOLTAGE)) {
                            lfBus.setGeneratorVoltageControlEnabled(false);
                        }
                    }
                    context.getNetwork().validate(LoadFlowModel.AC, null);
                    return true;
                }
                return false;
            });
        }

        private boolean onShuntUpdate(ShuntCompensator shunt, String attribute) {
            return onInjectionUpdate(shunt, attribute, (context, lfBus) -> {
                if (attribute.equals("sectionCount")) {
                    if (lfBus.getControllerShunt().isEmpty()) {
                        LfShunt lfShunt = lfBus.getShunt().orElseThrow();
                        lfShunt.reInit();
                        return true;
                    } else {
                        LOGGER.info("Shunt compensator {} is controlling voltage or connected to a bus containing a shunt compensator" +
                                "with an active voltage control: not supported", shunt.getId());
                    }
                }
                return false;
            });
        }

        private boolean onSwitchUpdate(String switchId, boolean open) {
            boolean found = false;
            for (AcLoadFlowContext context : contexts) {
                LfNetwork lfNetwork = context.getNetwork();
                LfBranch lfBranch = lfNetwork.getBranchById(switchId);
                if (lfBranch != null) {
                    updateSwitch(open, lfNetwork, lfBranch);
                    context.setNetworkUpdated(true);
                    found = true;
                }
            }
            if (!found) {
                LOGGER.warn("Cannot open switch '{}'", switchId);
            }
            return found;
        }

        private static void updateSwitch(boolean open, LfNetwork lfNetwork, LfBranch lfBranch) {
            var connectivity = lfNetwork.getConnectivity();
            connectivity.startTemporaryChanges();
            try {
                if (open) {
                    connectivity.removeEdge(lfBranch);
                } else {
                    connectivity.addEdge(lfBranch.getBus1(), lfBranch.getBus2(), lfBranch);
                }
                LfAction.updateBusesAndBranchStatus(connectivity);
            } finally {
                connectivity.undoTemporaryChanges();
            }
        }

        @Override
        public void onUpdate(Identifiable identifiable, String attribute, String variantId, Object oldValue, Object newValue) {
            if (contexts == null || pause) {
                return;
            }
            boolean done = false;
            switch (attribute) {
                case "v",
                     "angle",
                     "p",
                     "q",
                     "p1",
                     "q1",
                     "p2",
                     "q2" -> done = true; // ignore because it is related to state update and won't affect LF calculation
                default -> {
                    if (identifiable.getType() == IdentifiableType.GENERATOR) {
                        Generator generator = (Generator) identifiable;
                        if (attribute.equals("targetV")
                                && onGeneratorUpdate(generator, attribute, oldValue, newValue)) {
                            done = true;
                        }
                    } else if (identifiable.getType() == IdentifiableType.SHUNT_COMPENSATOR) {
                        ShuntCompensator shunt = (ShuntCompensator) identifiable;
                        if (attribute.equals("sectionCount")
                                && onShuntUpdate(shunt, attribute)) {
                            done = true;
                        }
                    } else if (identifiable.getType() == IdentifiableType.SWITCH
                            && attribute.equals("open")) {
                        if (onSwitchUpdate(identifiable.getId(), (boolean) newValue)) {
                            done = true;
                        }
                    }
                }
            }

            if (!done) {
                reset();
            }
        }

        private void onPropertyChange() {
            // nothing to do there could not have any impact on LF calculation
        }

        @Override
        public void onElementAdded(Identifiable identifiable, String attribute, Object newValue) {
            onPropertyChange();
        }

        @Override
        public void onElementReplaced(Identifiable identifiable, String attribute, Object oldValue, Object newValue) {
            onPropertyChange();
        }

        @Override
        public void onElementRemoved(Identifiable identifiable, String attribute, Object oldValue) {
            onPropertyChange();
        }

        private void onVariantChange() {
            // we reset
            // TODO to study later if we can do better
            reset();
        }

        @Override
        public void onVariantCreated(String sourceVariantId, String targetVariantId) {
            onVariantChange();
        }

        @Override
        public void onVariantOverwritten(String sourceVariantId, String targetVariantId) {
            onVariantChange();
        }

        @Override
        public void onVariantRemoved(String variantId) {
            onVariantChange();
        }

        public void close() {
            reset();
            Network network = networkRef.get();
            if (network != null && tmpVariantId != null) {
                network.getVariantManager().removeVariant(tmpVariantId);
            }
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    private final Lock lock = new ReentrantLock();

    private void evictDeadEntries() {
        Iterator<Entry> it = entries.iterator();
        while (it.hasNext()) {
            Entry entry = it.next();
            if (entry.getNetworkRef().get() == null) {
                // release all resources
                entry.close();
                it.remove();
                LOGGER.info("Dead network removed from cache ({} remains)", entries.size());
            }
        }
    }

    public int getEntryCount() {
        lock.lock();
        try {
            evictDeadEntries();
            return entries.size();
        } finally {
            lock.unlock();
        }
    }

    public Optional<Entry> findEntry(Network network) {
        String variantId = network.getVariantManager().getWorkingVariantId();
        return entries.stream()
                .filter(e -> e.getNetworkRef().get() == network && e.getWorkingVariantId().equals(variantId))
                .findFirst();
    }

    public Entry get(Network network, LoadFlowParameters parameters) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(parameters);

        Entry entry;
        lock.lock();
        try {
            evictDeadEntries();

            entry = findEntry(network).orElse(null);

            // invalid cache if parameters have changed
            // TODO to refine later by comparing in detail parameters that have changed
            if (entry != null && !OpenLoadFlowParameters.equals(parameters, entry.getParameters())) {
                // release all resources
                entry.close();
                entries.remove(entry);
                entry = null;
                LOGGER.info("Network cache evicted because of parameters change");
            }

            if (entry == null) {
                entry = new Entry(network, OpenLoadFlowParameters.clone(parameters));
                entries.add(entry);
                network.addListener(entry);

                LOGGER.info("Network cache created for network '{}' and variant '{}'",
                        network.getId(), network.getVariantManager().getWorkingVariantId());

                return entry;
            }
        } finally {
            lock.unlock();
        }

        // restart from previous state
        if (entry.getContexts() != null) {
            LOGGER.info("Network cache reused for network '{}' and variant '{}'",
                    network.getId(), network.getVariantManager().getWorkingVariantId());

            for (AcLoadFlowContext context : entry.getContexts()) {
                AcLoadFlowResult result = context.getResult();
                if (result != null && result.getSolverStatus() == AcSolverStatus.CONVERGED) {
                    context.getParameters().setVoltageInitializer(new PreviousValueVoltageInitializer(true));
                }
            }
        } else {
            LOGGER.info("Network cache cannot be reused for network '{}' because invalided", network.getId());
        }

        return entry;
    }

    public void clear() {
        lock.lock();
        try {
            for (var entry : entries) {
                entry.close();
            }
            entries.clear();
        } finally {
            lock.unlock();
        }
    }
}
