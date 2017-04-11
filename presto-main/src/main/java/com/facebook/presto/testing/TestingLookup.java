/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.presto.testing;

import com.facebook.presto.Session;
import com.facebook.presto.cost.CostCalculator;
import com.facebook.presto.cost.PlanNodeCostEstimate;
import com.facebook.presto.cost.PlanNodeStatsEstimate;
import com.facebook.presto.cost.StatsCalculator;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.sql.planner.Symbol;
import com.facebook.presto.sql.planner.iterative.Lookup;
import com.facebook.presto.sql.planner.plan.PlanNode;

import java.util.HashMap;
import java.util.Map;

import static com.facebook.presto.util.ImmutableCollectors.toImmutableList;
import static java.util.Objects.requireNonNull;

public class TestingLookup
        implements Lookup
{
    private final StatsCalculator statsCalculator;
    private final CostCalculator costCalculator;
    private final Map<PlanNode, PlanNodeStatsEstimate> stats = new HashMap<>();
    private final Map<PlanNode, PlanNodeCostEstimate> costs = new HashMap<>();

    public TestingLookup(StatsCalculator statsCalculator, CostCalculator costCalculator)
    {
        this.statsCalculator = requireNonNull(statsCalculator, "statsCalculator is null");
        this.costCalculator = requireNonNull(costCalculator, "costCalculator is null");
    }

    public void setStats(PlanNode node, PlanNodeStatsEstimate stats)
    {
        this.stats.put(node, stats);
    }

    public void setCost(PlanNode node, PlanNodeCostEstimate cost)
    {
        costs.put(node, cost);
    }

    @Override
    public PlanNode resolve(PlanNode node)
    {
        return node;
    }

    @Override
    public PlanNodeStatsEstimate getStats(Session session, Map<Symbol, Type> types, PlanNode planNode)
    {
        return stats.computeIfAbsent(planNode, node -> statsCalculator.calculateStats(
                node,
                node.getSources().stream()
                        .map(sourceNode -> getStats(session, types, sourceNode))
                        .collect(toImmutableList()),
                session,
                types));
    }

    @Override
    public PlanNodeCostEstimate getCost(Session session, Map<Symbol, Type> types, PlanNode planNode)
    {
        return costs.computeIfAbsent(resolve(planNode), node -> costCalculator.calculateCost(
                session,
                types,
                node,
                this));
    }
}
