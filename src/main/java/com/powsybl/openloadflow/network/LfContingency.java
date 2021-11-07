/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.network;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.powsybl.openloadflow.graph.GraphDecrementalConnectivity;
import com.powsybl.openloadflow.util.PropagatedContingency;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class LfContingency {

    private final String id;

    private final int index;

    private final Set<LfBus> buses;

    private final Set<LfBranch> branches;

    private double activePowerLoss;

    public LfContingency(String id, int index, Set<LfBus> buses, Set<LfBranch> branches) {
        this.id = Objects.requireNonNull(id);
        this.index = index;
        this.buses = Objects.requireNonNull(buses);
        this.branches = Objects.requireNonNull(branches);
        double lose = 0;
        for (LfBus bus : buses) {
            lose += bus.getGenerationTargetP() - bus.getLoadTargetP();
        }
        this.activePowerLoss = lose;
    }

    public String getId() {
        return id;
    }

    public int getIndex() {
        return index;
    }

    public Set<LfBus> getBuses() {
        return buses;
    }

    public Set<LfBranch> getBranches() {
        return branches;
    }

    public double getActivePowerLoss() {
        return activePowerLoss;
    }

    public static Optional<LfContingency> create(PropagatedContingency propagatedContingency, LfNetwork network,
                                                 GraphDecrementalConnectivity<LfBus> connectivity, boolean useSmallComponents) {
        // find contingency branches that are part of this network
        Set<LfBranch> branches = new HashSet<>(1);
        for (String branchId : propagatedContingency.getBranchIdsToOpen()) {
            LfBranch branch = network.getBranchById(branchId);
            if (branch != null) {
                branches.add(branch);
            }
        }

        // check if contingency split this network into multiple components
        if (branches.isEmpty()) {
            return Optional.empty();
        }

        // update connectivity with triggered branches
        for (LfBranch branch : branches) {
            connectivity.cut(branch.getBus1(), branch.getBus2());
        }

        // add to contingency description buses and branches that won't be part of the main connected
        // component in post contingency state
        Set<LfBus> buses;
        if (useSmallComponents) {
            buses = connectivity.getSmallComponents().stream().flatMap(Set::stream).collect(Collectors.toSet());
        } else {
            int slackBusComponent = connectivity.getComponentNumber(network.getSlackBus());
            buses = network.getBuses().stream().filter(b -> connectivity.getComponentNumber(b) != slackBusComponent).collect(Collectors.toSet());
        }
        buses.forEach(b -> branches.addAll(b.getBranches()));

        // reset connectivity to discard triggered branches
        connectivity.reset();

        return Optional.of(new LfContingency(propagatedContingency.getContingency().getId(), propagatedContingency.getIndex(), buses, branches));
    }

    public void writeJson(Writer writer) {
        Objects.requireNonNull(writer);
        try (JsonGenerator jsonGenerator = new JsonFactory()
                .createGenerator(writer)
                .useDefaultPrettyPrinter()) {
            jsonGenerator.writeStartObject();

            jsonGenerator.writeStringField("id", id);

            jsonGenerator.writeFieldName("buses");
            int[] sortedBuses = buses.stream().mapToInt(LfBus::getNum).sorted().toArray();
            jsonGenerator.writeArray(sortedBuses, 0, sortedBuses.length);

            jsonGenerator.writeFieldName("branches");
            int[] sortedBranches = branches.stream().mapToInt(LfBranch::getNum).sorted().toArray();
            jsonGenerator.writeArray(sortedBranches, 0, sortedBranches.length);

            jsonGenerator.writeEndObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}