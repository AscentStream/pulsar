/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.broker.loadbalance;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import com.google.common.collect.Sets;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;

import java.util.Set;
import org.apache.pulsar.broker.BrokerData;
import org.apache.pulsar.broker.BundleData;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.broker.loadbalance.impl.AvgShedder;
import org.apache.pulsar.broker.TimeAverageBrokerData;
import org.apache.pulsar.broker.loadbalance.impl.LeastLongTermMessageRate;
import org.apache.pulsar.broker.loadbalance.impl.LeastResourceUsageWithWeight;
import org.apache.pulsar.policies.data.loadbalancer.LocalBrokerData;
import org.apache.pulsar.policies.data.loadbalancer.ResourceUsage;
import org.testng.annotations.Test;

@Test(groups = "broker")
public class ModularLoadManagerStrategyTest {

    public void testAvgShedderWithPreassignedBroker() throws Exception {
        ModularLoadManagerStrategy strategy = new AvgShedder();
        Field field = AvgShedder.class.getDeclaredField("bundleBrokerMap");
        field.setAccessible(true);
        Map<BundleData, String> bundleBrokerMap = (Map<BundleData, String>) field.get(strategy);
        BundleData bundleData = new BundleData();
        // assign bundle to broker1 in bundleBrokerMap.
        bundleBrokerMap.put(bundleData, "1");
        assertEquals(strategy.selectBroker(Sets.newHashSet("1", "2", "3"), bundleData, null, null), Optional.of("1"));
        assertEquals(bundleBrokerMap.get(bundleData), "1");

        // remove broker1 in candidates, only broker2 is candidate.
        assertEquals(strategy.selectBroker(Sets.newHashSet("2"), bundleData, null, null), Optional.of("2"));
        assertEquals(bundleBrokerMap.get(bundleData), "2");
    }

    public void testAvgShedderWithoutPreassignedBroker() throws Exception {
        ModularLoadManagerStrategy strategy = new AvgShedder();
        Field field = AvgShedder.class.getDeclaredField("bundleBrokerMap");
        field.setAccessible(true);
        Map<BundleData, String> bundleBrokerMap = (Map<BundleData, String>) field.get(strategy);
        BundleData bundleData = new BundleData();
        Set<String> candidates = new HashSet<>();
        candidates.add("1");
        candidates.add("2");
        candidates.add("3");

        // select broker from candidates randomly.
        Optional<String> selectedBroker = strategy.selectBroker(candidates, bundleData, null, null);
        assertTrue(selectedBroker.isPresent());
        assertTrue(candidates.contains(selectedBroker.get()));
        assertEquals(bundleBrokerMap.get(bundleData), selectedBroker.get());

        // remove original broker in candidates
        candidates.remove(selectedBroker.get());
        selectedBroker = strategy.selectBroker(candidates, bundleData, null, null);
        assertTrue(selectedBroker.isPresent());
        assertTrue(candidates.contains(selectedBroker.get()));
        assertEquals(bundleBrokerMap.get(bundleData), selectedBroker.get());
    }

    // Test that least long term message rate works correctly.
    public void testLeastLongTermMessageRate() {
        BundleData bundleData = new BundleData();
        BrokerData brokerData1 = initBrokerData();
        BrokerData brokerData2 = initBrokerData();
        BrokerData brokerData3 = initBrokerData();
        brokerData1.getTimeAverageData().setLongTermMsgRateIn(100);
        brokerData2.getTimeAverageData().setLongTermMsgRateIn(200);
        brokerData3.getTimeAverageData().setLongTermMsgRateIn(300);
        LoadData loadData = new LoadData();
        Map<String, BrokerData> brokerDataMap = loadData.getBrokerData();
        brokerDataMap.put("1", brokerData1);
        brokerDataMap.put("2", brokerData2);
        brokerDataMap.put("3", brokerData3);
        ServiceConfiguration conf = new ServiceConfiguration();
        ModularLoadManagerStrategy strategy = new LeastLongTermMessageRate();
        assertEquals(strategy.selectBroker(brokerDataMap.keySet(), bundleData, loadData, conf), Optional.of("1"));
        brokerData1.getTimeAverageData().setLongTermMsgRateIn(400);
        assertEquals(strategy.selectBroker(brokerDataMap.keySet(), bundleData, loadData, conf), Optional.of("2"));
        brokerData2.getLocalData().setCpu(new ResourceUsage(90, 100));
        assertEquals(strategy.selectBroker(brokerDataMap.keySet(), bundleData, loadData, conf), Optional.of("3"));
    }

    // Test that least resource usage with weight works correctly.
    public void testLeastResourceUsageWithWeight() {
        BundleData bundleData = new BundleData();
        BrokerData brokerData1 = initBrokerData(10, 100);
        BrokerData brokerData2 = initBrokerData(30, 100);
        BrokerData brokerData3 = initBrokerData(60, 100);
        LoadData loadData = new LoadData();
        Map<String, BrokerData> brokerDataMap = loadData.getBrokerData();
        brokerDataMap.put("1", brokerData1);
        brokerDataMap.put("2", brokerData2);
        brokerDataMap.put("3", brokerData3);
        ServiceConfiguration conf = new ServiceConfiguration();
        conf.setLoadBalancerCPUResourceWeight(1.0);
        conf.setLoadBalancerMemoryResourceWeight(0.1);
        conf.setLoadBalancerDirectMemoryResourceWeight(0.1);
        conf.setLoadBalancerBandwithInResourceWeight(1.0);
        conf.setLoadBalancerBandwithOutResourceWeight(1.0);
        conf.setLoadBalancerHistoryResourcePercentage(0.5);
        conf.setLoadBalancerAverageResourceUsageDifferenceThresholdPercentage(5);

        ModularLoadManagerStrategy strategy = new LeastResourceUsageWithWeight();
        assertEquals(strategy.selectBroker(brokerDataMap.keySet(), bundleData, loadData, conf), Optional.of("1"));

        brokerData1 = initBrokerData(20,100);
        brokerData2 = initBrokerData(30,100);
        brokerData3 = initBrokerData(50,100);
        brokerDataMap.put("1", brokerData1);
        brokerDataMap.put("2", brokerData2);
        brokerDataMap.put("3", brokerData3);
        assertEquals(strategy.selectBroker(brokerDataMap.keySet(), bundleData, loadData, conf), Optional.of("1"));

        brokerData1 = initBrokerData(30,100);
        brokerData2 = initBrokerData(30,100);
        brokerData3 = initBrokerData(40,100);
        brokerDataMap.put("1", brokerData1);
        brokerDataMap.put("2", brokerData2);
        brokerDataMap.put("3", brokerData3);
        assertEquals(strategy.selectBroker(brokerDataMap.keySet(), bundleData, loadData, conf), Optional.of("1"));

        brokerData1 = initBrokerData(30,100);
        brokerData2 = initBrokerData(30,100);
        brokerData3 = initBrokerData(40,100);
        brokerDataMap.put("1", brokerData1);
        brokerDataMap.put("2", brokerData2);
        brokerDataMap.put("3", brokerData3);
        assertEquals(strategy.selectBroker(brokerDataMap.keySet(), bundleData, loadData, conf), Optional.of("1"));

        brokerData1 = initBrokerData(35,100);
        brokerData2 = initBrokerData(20,100);
        brokerData3 = initBrokerData(45,100);
        brokerDataMap.put("1", brokerData1);
        brokerDataMap.put("2", brokerData2);
        brokerDataMap.put("3", brokerData3);
        assertEquals(strategy.selectBroker(brokerDataMap.keySet(), bundleData, loadData, conf), Optional.of("2"));
    }

    public void testActiveBrokersChange() throws Exception {
        LoadData loadData = new LoadData();
        Map<String, BrokerData> brokerDataMap = loadData.getBrokerData();
        brokerDataMap.put("1", initBrokerData());
        brokerDataMap.put("2", initBrokerData());
        brokerDataMap.put("3", initBrokerData());
        ServiceConfiguration conf = new ServiceConfiguration();
        LeastResourceUsageWithWeight strategy = new LeastResourceUsageWithWeight();
        strategy.selectBroker(brokerDataMap.keySet(), new BundleData(), loadData, conf);
        Field field = LeastResourceUsageWithWeight.class.getDeclaredField("brokerAvgResourceUsageWithWeight");
        field.setAccessible(true);
        Map<String, Double> map = (Map<String, Double>) field.get(strategy);
        assertEquals(map.size(), 3);
        strategy.onActiveBrokersChange(new HashSet<>());
        assertEquals(map.size(), 0);
    }

    private BrokerData initBrokerData(double usage, double limit) {
        LocalBrokerData localBrokerData = new LocalBrokerData();
        localBrokerData.setCpu(new ResourceUsage(usage, limit));
        localBrokerData.setMemory(new ResourceUsage(usage, limit));
        localBrokerData.setDirectMemory(new ResourceUsage(usage, limit));
        localBrokerData.setBandwidthIn(new ResourceUsage(usage, limit));
        localBrokerData.setBandwidthOut(new ResourceUsage(usage, limit));
        BrokerData brokerData = new BrokerData(localBrokerData);
        TimeAverageBrokerData timeAverageBrokerData = new TimeAverageBrokerData();
        brokerData.setTimeAverageData(timeAverageBrokerData);
        return brokerData;
    }

    private BrokerData initBrokerData() {
        LocalBrokerData localBrokerData = new LocalBrokerData();
        localBrokerData.setCpu(new ResourceUsage());
        localBrokerData.setMemory(new ResourceUsage());
        localBrokerData.setBandwidthIn(new ResourceUsage());
        localBrokerData.setBandwidthOut(new ResourceUsage());
        BrokerData brokerData = new BrokerData(localBrokerData);
        TimeAverageBrokerData timeAverageBrokerData = new TimeAverageBrokerData();
        brokerData.setTimeAverageData(timeAverageBrokerData);
        return brokerData;
    }
}
