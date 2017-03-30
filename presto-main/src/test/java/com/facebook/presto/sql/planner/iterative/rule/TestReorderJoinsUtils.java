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

package com.facebook.presto.sql.planner.iterative.rule;

import com.facebook.presto.Session;
import com.facebook.presto.sql.planner.Plan;
import com.facebook.presto.sql.planner.PlanNodeIdAllocator;
import com.facebook.presto.sql.planner.iterative.rule.test.PlanBuilder;
import com.facebook.presto.sql.planner.plan.JoinNode;
import com.facebook.presto.sql.tree.ComparisonExpression;
import com.facebook.presto.testing.LocalQueryRunner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.testng.annotations.Test;

import java.util.Set;

import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.sql.planner.assertions.PlanAssert.assertPlan;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.equiJoinClause;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.join;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.joinGraph;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.values;
import static com.facebook.presto.sql.planner.iterative.rule.ReorderJoinsUtils.createBinaryJoin;
import static com.facebook.presto.sql.planner.iterative.rule.ReorderJoinsUtils.generatePartitions;
import static com.facebook.presto.sql.tree.ComparisonExpressionType.GREATER_THAN;
import static com.facebook.presto.testing.TestingSession.testSessionBuilder;
import static org.testng.Assert.assertEquals;

public class TestReorderJoinsUtils
{
    @Test
    public void testGeneratePartitions()
    {
        Set<Set<Integer>> partitions = generatePartitions(4);
        assertEquals(partitions,
                ImmutableSet.of(
                        ImmutableSet.of(0),
                        ImmutableSet.of(0, 1),
                        ImmutableSet.of(0, 2),
                        ImmutableSet.of(0, 3),
                        ImmutableSet.of(0, 1, 2),
                        ImmutableSet.of(0, 1, 3),
                        ImmutableSet.of(0, 2, 3)));
    }

    @Test
    public void testCreateBinaryJoin()
    {
        Session session = testSessionBuilder().build();
        LocalQueryRunner queryRunner = new LocalQueryRunner(session);
        PlanNodeIdAllocator idAllocator = new PlanNodeIdAllocator();
        PlanBuilder planBuilder = new PlanBuilder(idAllocator);
        JoinGraphNode joinGraphNode = planBuilder.joinGraph(
                ImmutableList.of(
                        planBuilder.values(planBuilder.symbol("A1", BIGINT)),
                        planBuilder.values(planBuilder.symbol("B1", BIGINT)),
                        planBuilder.values(planBuilder.symbol("C1", BIGINT)),
                        planBuilder.values(planBuilder.symbol("D1", BIGINT))),
                ImmutableList.of(
                        new JoinNode.EquiJoinClause(planBuilder.symbol("A1", BIGINT), planBuilder.symbol("B1", BIGINT)),
                        new JoinNode.EquiJoinClause(planBuilder.symbol("A1", BIGINT), planBuilder.symbol("C1", BIGINT)),
                        new JoinNode.EquiJoinClause(planBuilder.symbol("D1", BIGINT), planBuilder.symbol("C1", BIGINT)),
                        new JoinNode.EquiJoinClause(planBuilder.symbol("B1", BIGINT), planBuilder.symbol("D1", BIGINT))
                ),
                ImmutableList.of(
                        new ComparisonExpression(GREATER_THAN, planBuilder.symbol("A1", BIGINT).toSymbolReference(), planBuilder.symbol("C1", BIGINT).toSymbolReference())
                ));
        JoinNode actual = createBinaryJoin(joinGraphNode, ImmutableSet.of(0, 2), idAllocator);
        assertPlan(
                session,
                queryRunner.getMetadata(),
                queryRunner.getLookup(),
                new Plan(actual, planBuilder.getSymbols()),
                join(
                        JoinNode.Type.INNER,
                        ImmutableList.of(equiJoinClause("A1", "B1"), equiJoinClause("C1", "D1")),
                        joinGraph(
                                ImmutableList.of(equiJoinClause("A1", "C1")),
                                ImmutableList.of("A1 > C1"),
                                values(ImmutableMap.of("A1", 0)),
                                values(ImmutableMap.of("C1", 0))),
                        joinGraph(
                                ImmutableList.of(equiJoinClause("B1", "D1")),
                                ImmutableList.of(),
                                values(ImmutableMap.of("B1", 0)),
                                values(ImmutableMap.of("D1", 0)))));
    }
}
