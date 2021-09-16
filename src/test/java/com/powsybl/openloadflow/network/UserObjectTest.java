/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.network;

import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 *
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
class UserObjectTest {

    @Test
    void test() {
        Network network = EurostagTutorialExample1Factory.create();
        LfNetwork lfNetwork = LfNetwork.load(network, new FirstSlackBusSelector()).get(0);
        assertNull(lfNetwork.getUserObject());
        lfNetwork.setUserObject("test");
        assertEquals("test", lfNetwork.getUserObject());
        LfBus lfBus = lfNetwork.getBus(0);
        assertNull(lfBus.getUserObject());
        lfBus.setUserObject("hello");
        assertEquals("hello", lfBus.getUserObject());
    }
}